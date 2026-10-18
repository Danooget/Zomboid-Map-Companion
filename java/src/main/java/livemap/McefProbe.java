package livemap;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.browser.CefRequestContext;
import org.cef.callback.CefQueryCallback;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.lwjglx.input.Keyboard;
import zombie.input.GameKeyboard;
import se.krka.kahlua.integration.annotations.LuaMethod;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** CinemaMod's headless-safe CEF OSR browser, with matched viewport and keyboard focus. */
public final class McefProbe {
    private static CefApp app;
    private static CefClient client;
    private static ProbeBrowser browser;
    private static CefMessageRouter focusRouter;
    private static boolean started;
    private static boolean failed;
    private static long tickCount;
    private static volatile int viewportWidth = 800;
    private static volatile int viewportHeight = 600;
    private static volatile boolean textFocused;
    private static volatile int focusLogCount;
    // PZ's UIManager dispatches keyboard input through Lua UI tables, not
    // reliably through a Java UIElement subclass. The Lua sink sends presses
    // here; game-tick polling supplies releases (eatKeyPress skips releases).
    private static final Object KEY_LOCK = new Object();
    private static final boolean[] heldKeys = new boolean[512];
    private static final long[] nextRepeatAt = new long[512];
    private static int routedKeyEvents;
    private static final long INITIAL_REPEAT_NS = 420_000_000L;
    private static final long REPEAT_INTERVAL_NS = 65_000_000L;

    // Javascript executes only in the website, and reports focus changes through
    // CEF's existing cross-process message router. It does NOT read input values.
    private static final String FOCUS_SCRIPT = """
        (function() {
          function editable() {
            var e = document.activeElement;
            if (!e) return false;
            if (e.isContentEditable) return true;
            var tag = (e.tagName || '').toLowerCase();
            if (tag === 'textarea') return !e.disabled && !e.readOnly;
            if (tag !== 'input') return false;
            var type = (e.type || 'text').toLowerCase();
            return !e.disabled && !e.readOnly && ![
              'button','checkbox','radio','submit','reset','file','hidden',
              'image','range','color','date','datetime-local','time','month','week'
            ].includes(type);
          }
          function report() {
            if (typeof window.liveWebMapQuery !== 'function') return;
            window.liveWebMapQuery({request: 'LWM_FOCUS:' + (editable() ? '1' : '0')});
          }
          if (!window.__liveWebMapFocusInstalled) {
            window.__liveWebMapFocusInstalled = true;
            document.addEventListener('focusin', function() { setTimeout(report, 0); }, true);
            document.addEventListener('focusout', function() { setTimeout(report, 0); }, true);
            document.addEventListener('pointerup', function() { setTimeout(report, 0); }, true);
          }
          report();
          setTimeout(report, 90);
        })();
        """;

    public McefProbe() { }
    public static int getViewportWidth() { return viewportWidth; }
    public static int getViewportHeight() { return viewportHeight; }
    public static boolean isTextFocused() { return textFocused && LiveWebMapPanel.isOpen(); }

    /** Clear game keyboard capture without asynchronously blurring a newly clicked field. */
    public static void suspendTextCapture() {
        textFocused = false;
        releaseAllKeys();
    }

    public static void clearTextFocus() {
        textFocused = false;
        releaseAllKeys();
        ProbeBrowser current = browser;
        if (current != null && started && !failed) {
            // Release Chromium's native input focus and the DOM's active field.
            try {
                current.executeJavaScript("if(document.activeElement && document.activeElement.blur)document.activeElement.blur();",
                        current.getURL(), 0);
                current.setFocus(false);
            } catch (Throwable e) {
                System.err.println("[LiveWebMap-MCEF] Could not clear text focus: " + e);
            }
        }
    }

    /** Called after browser click; the browser determines whether a field is editable. */
    public static void probeTextFocus() {
        ProbeBrowser current = browser;
        if (!started || failed || current == null || !LiveWebMapPanel.isOpen()) return;
        try {
            current.executeJavaScript(FOCUS_SCRIPT, current.getURL(), 0);
        } catch (Throwable e) {
            textFocused = false;
            System.err.println("[LiveWebMap-MCEF] Focus probe failed: " + e);
        }
    }

    /** Resize matches panel content pixels, with a hard 4K safety limit. */
    public static void setViewportSize(int requestedWidth, int requestedHeight) {
        int width = Math.max(320, Math.min(3840, requestedWidth));
        int height = Math.max(240, Math.min(2160, requestedHeight));
        if (width == viewportWidth && height == viewportHeight) return;
        viewportWidth = width;
        viewportHeight = height;
        McefFrameUpload.setTargetSize(width, height);
        ProbeBrowser current = browser;
        if (current != null && started && !failed) current.resizeViewport(width, height);
        System.out.println("[LiveWebMap-MCEF] AUTO viewport: " + width + "x" + height);
    }

    @LuaMethod(name = "liveWebMapMcefStart", global = true)
    public static void start() {
        if (started || failed) return;
        try {
            Path cefDir = Paths.get(System.getProperty("user.home"),
                    "LiveWebMapMCEFDev", "thirdparty", "mcef-natives", "windows_amd64");
            if (!Files.isRegularFile(cefDir.resolve("jcef.dll"))
                    || !Files.isRegularFile(cefDir.resolve("libcef.dll"))
                    || !Files.isRegularFile(cefDir.resolve("jcef_helper.exe"))) {
                throw new IllegalStateException("Missing CinemaMod native files in " + cefDir);
            }

            System.setProperty("jcef.path", cefDir.toAbsolutePath().toString());
            System.out.println("[LiveWebMap-MCEF] Native path: " + cefDir);
            System.out.println("[LiveWebMap-MCEF] JVM headless property: "
                    + System.getProperty("java.awt.headless"));
            String[] switches = { "--disable-gpu" };
            if (!CefApp.startup(switches)) throw new IllegalStateException("CefApp.startup returned false");
            System.out.println("[LiveWebMap-MCEF] Native startup succeeded");
            CefSettings settings = new CefSettings();
            settings.windowless_rendering_enabled = true;
            settings.cache_path = Paths.get(System.getProperty("user.home"),
                    "Zomboid", "LiveWebMap-MCEF-cache").toAbsolutePath().toString();
            app = CefApp.getInstance(switches, settings);
            client = app.createClient();
            LiveWebMapGpsBridge.install(client);
            LiveWebMapProtection.install(client); // Must precede browser creation.

            // The built-in CEF router provides an IPC channel from JS to Java;
            // create it before opening the webpage.
            try {
                focusRouter = CefMessageRouter.create(
                        new CefMessageRouter.CefMessageRouterConfig("liveWebMapQuery", "liveWebMapCancel"));
                if (focusRouter != null) {
                    focusRouter.addHandler(new CefMessageRouterHandlerAdapter() {
                        @Override
                        public boolean onQuery(CefBrowser origin, CefFrame frame, long queryId,
                                String request, boolean persistent, CefQueryCallback callback) {
                            if (!request.startsWith("LWM_FOCUS:")) return false;
                            if (origin == browser && LiveWebMapPanel.isOpen()
                                    && origin.getURL().startsWith("https://projectzomboidmap.com/")) {
                                boolean enabled = request.equals("LWM_FOCUS:1");
                                if (textFocused != enabled && focusLogCount++ < 30) {
                                    System.out.println("[LiveWebMap-MCEF] Web text focus=" + enabled);
                                }
                                textFocused = enabled;
                            }
                            callback.success("ok");
                            return true;
                        }
                    }, true);
                    client.addMessageRouter(focusRouter);
                    System.out.println("[LiveWebMap-MCEF] JS keyboard-focus router ready");
                }
            } catch (Throwable e) {
                focusRouter = null;
                System.err.println("[LiveWebMap-MCEF] Focus router unavailable; typing disabled: " + e);
            }

            System.out.println("[LiveWebMap-MCEF] Client created; state=" + CefApp.getState());
            McefFrameUpload.setTargetSize(viewportWidth, viewportHeight);
            String mapUrl = "https://projectzomboidmap.com/";
            System.out.println("[LiveWebMap-MCEF] Loading website: " + mapUrl);
            browser = new ProbeBrowser(client, mapUrl);
            browser.createImmediately();
            started = true;
            System.out.println("[LiveWebMap-MCEF] OSR browser created; awaiting PAINT callback");
        } catch (Throwable e) {
            failed = true;
            System.err.println("[LiveWebMap-MCEF] START FAILED: " + e);
            e.printStackTrace(System.err);
        }
    }

    /** Called independently of the CEF message loop to diagnose UI conflicts. */
    @LuaMethod(name = "liveWebMapUiHealthTick", global = true)
    public static void liveWebMapUiHealthTick() {
        LiveWebMapPanel.healthTick();
    }
    @LuaMethod(name = "liveWebMapMcefTick", global = true)
    public static void tick() {
        if (!started || failed || app == null) return;
        try {
            app.N_DoMessageLoopWork();
            LiveWebMapPanel.pollOutsideClick();
            pollHeldKeys();
            McefFrameUpload.uploadLatest();
            LiveWebMapGpsBridge.tick(browser);
            tickCount++;
            if (tickCount == 300) {
                System.out.println("[LiveWebMap-MCEF] 300 message-loop ticks; paint count="
                        + browser.paintCount);
            }
        } catch (Throwable e) {
            failed = true;
            System.err.println("[LiveWebMap-MCEF] MESSAGE LOOP FAILED: " + e);
            e.printStackTrace(System.err);
        }
    }

    /** Local player sample from LiveWebMapDirectGps.lua, exposed by ZombieBuddy. */
    @LuaMethod(name = "liveWebMapMcefGpsSample", global = true)
    public static void gpsSample(String name, double x, double y, double z,
                                 double fx, double fy) {
        LiveWebMapGpsBridge.samplePlayer(name, x, y, z, fx, fy);
    }

    /** Refresh the map without destroying Chromium or the direct GPS bridge. */
    public static void reloadMap() {
        ProbeBrowser current = browser;
        if (!started || failed || current == null) return;
        clearTextFocus();
        try {
            current.reload();
            System.out.println("[LiveWebMap-MCEF] Map refreshed by user");
        } catch (Throwable e) {
            System.err.println("[LiveWebMap-MCEF] Refresh failed: " + e);
        }
    }

    public static void mouseMove(int x, int y, boolean leftDown, boolean rightDown) {
        ProbeBrowser active = browser;
        if (started && !failed && active != null) active.injectMove(x, y, leftDown, rightDown);
    }
    public static void mouseButton(int x, int y, int button, boolean pressed) {
        ProbeBrowser active = browser;
        if (started && !failed && active != null) active.injectButton(x, y, button, pressed);
    }
    public static void mouseWheel(int x, int y, double delta) {
        ProbeBrowser active = browser;
        if (started && !failed && active != null && delta != 0) active.injectWheel(x, y, delta);
    }

    /** Called from the Lua UI key-capture element, NOT the Java UIElement hooks. */
    @LuaMethod(name = "liveWebMapMcefKeyboardActive", global = true)
    public static boolean keyboardActive() {
        return isTextFocused() && started && !failed && browser != null;
    }

    /** Phase 1 = key down, phase 0 = key up. F7 always belongs to Zomboid. */
    @LuaMethod(name = "liveWebMapMcefForwardKey", global = true)
    public static void forwardKey(int pzKey, int phase) {
        if (pzKey == Keyboard.KEY_F7 || pzKey < 0 || pzKey >= heldKeys.length) return;
        synchronized (KEY_LOCK) {
            if (phase == 1) {
                if (!keyboardActive() || heldKeys[pzKey]) return;
                heldKeys[pzKey] = true;
                nextRepeatAt[pzKey] = System.nanoTime() + INITIAL_REPEAT_NS;
                emitKey(pzKey, true);
                if (++routedKeyEvents == 1) {
                    System.out.println("[LiveWebMap-MCEF] Lua keyboard capture received first key event");
                }
            } else if (phase == 0 && heldKeys[pzKey]) {
                heldKeys[pzKey] = false;
                emitKey(pzKey, false);
            }
        }
    }

    /** A release can be swallowed by GameKeyboard.eatKeyPress: detect it via polling. */
    private static void pollHeldKeys() {
        synchronized (KEY_LOCK) {
            boolean active = keyboardActive();
            long now = System.nanoTime();
            for (int key = 0; key < heldKeys.length; key++) {
                if (!heldKeys[key]) continue;
                if (!active || !GameKeyboard.isKeyDown(key)) {
                    heldKeys[key] = false;
                    emitKey(key, false);
                } else if (now >= nextRepeatAt[key]) {
                    emitKey(key, true);
                    nextRepeatAt[key] = now + REPEAT_INTERVAL_NS;
                }
            }
        }
    }

    private static void releaseAllKeys() {
        synchronized (KEY_LOCK) {
            for (int key = 0; key < heldKeys.length; key++) {
                if (!heldKeys[key]) continue;
                heldKeys[key] = false;
                emitKey(key, false);
            }
        }
    }

    /** Existing JNI bridge requires GLFW key codes, not Project Zomboid codes. */
    private static void emitKey(int pzKey, boolean pressed) {
        ProbeBrowser active = browser;
        if (!started || failed || active == null) return;
        String name = Keyboard.getKeyName(pzKey);
        if (name == null) return;
        int glfw = toGlfwKey(name);
        if (glfw < 0) return;
        int mods = modifiers();
        char typed = printable(name, (mods & 1) != 0);
        active.injectKey(glfw, typed, mods, pressed);
    }

    private static java.lang.reflect.Method glfwScancodeMethod;
    private static boolean scancodeLookedUp;

    private static synchronized long glfwScancode(int glfwCode) {
        if (!scancodeLookedUp) {
            scancodeLookedUp = true;
            try {
                Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
                glfwScancodeMethod = glfw.getMethod("glfwGetKeyScancode", int.class);
                System.out.println("[LiveWebMap-MCEF] GLFW keyboard scancode bridge ready");
            } catch (Throwable e) {
                System.err.println("[LiveWebMap-MCEF] GLFW scancode bridge unavailable: " + e);
            }
        }
        if (glfwScancodeMethod != null) {
            try {
                return ((Number) glfwScancodeMethod.invoke(null, glfwCode)).longValue();
            } catch (Throwable e) {
                System.err.println("[LiveWebMap-MCEF] Keyboard scancode lookup failed: " + e);
                glfwScancodeMethod = null;
            }
        }
        return 0;
    }

    private static int modifiers() {
        int mods = 0;
        if (GameKeyboard.isKeyDown(Keyboard.KEY_LSHIFT) || GameKeyboard.isKeyDown(Keyboard.KEY_RSHIFT)) mods |= 1;
        if (GameKeyboard.isKeyDown(Keyboard.KEY_LCONTROL) || GameKeyboard.isKeyDown(Keyboard.KEY_RCONTROL)) mods |= 2;
        if (GameKeyboard.isKeyDown(Keyboard.KEY_LMENU) || GameKeyboard.isKeyDown(Keyboard.KEY_RMENU)) mods |= 4;
        return mods;
    }

    private static int toGlfwKey(String name) {
        if (name.length() == 1) {
            char c = Character.toUpperCase(name.charAt(0));
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return c;
        }
        return switch (name.toUpperCase()) {
            case "SPACE" -> 32;
            case "RETURN", "NUMPADENTER" -> 257;
            case "BACK" -> 259;
            case "TAB" -> 258;
            case "DELETE" -> 261;
            case "INSERT" -> 260;
            case "LEFT" -> 263;
            case "RIGHT" -> 262;
            case "UP" -> 265;
            case "DOWN" -> 264;
            case "HOME" -> 268;
            case "END" -> 269;
            case "PRIOR" -> 266;
            case "NEXT" -> 267;
            case "ESCAPE" -> 256;
            case "MINUS" -> 45;
            case "EQUALS" -> 61;
            case "LBRACKET" -> 91;
            case "RBRACKET" -> 93;
            case "BACKSLASH" -> 92;
            case "SEMICOLON" -> 59;
            case "APOSTROPHE" -> 39;
            case "COMMA" -> 44;
            case "PERIOD" -> 46;
            case "SLASH" -> 47;
            case "GRAVE" -> 96;
            case "LSHIFT" -> 340;
            case "RSHIFT" -> 344;
            case "LCONTROL" -> 341;
            case "RCONTROL" -> 345;
            case "LMENU" -> 342;
            case "RMENU" -> 346;
            case "CAPITAL" -> 280;
            case "DECIMAL" -> 330;
            default -> {
                if (name.startsWith("NUMPAD") && name.length() == 7
                        && Character.isDigit(name.charAt(6))) yield 320 + (name.charAt(6) - '0');
                yield -1;
            }
        };
    }

    /** Basic US keyboard mapping. Uses Chromium's native keydown for nonprintable keys. */
    private static char printable(String name, boolean shift) {
        if (name.length() == 1) {
            char c = Character.toUpperCase(name.charAt(0));
            if (c >= 'A' && c <= 'Z') return shift ? c : Character.toLowerCase(c);
            if (c >= '0' && c <= '9') return shift ? ")!@#$%^&*(".charAt(c - '0') : c;
        }
        if (name.startsWith("NUMPAD") && name.length() == 7 && Character.isDigit(name.charAt(6)))
            return name.charAt(6);
        return switch (name.toUpperCase()) {
            case "SPACE" -> ' ';
            case "MINUS" -> shift ? '_' : '-';
            case "EQUALS" -> shift ? '+' : '=';
            case "LBRACKET" -> shift ? '{' : '[';
            case "RBRACKET" -> shift ? '}' : ']';
            case "BACKSLASH" -> shift ? '|' : '\\';
            case "SEMICOLON" -> shift ? ':' : ';';
            case "APOSTROPHE" -> shift ? '"' : '\'';
            case "COMMA" -> shift ? '<' : ',';
            case "PERIOD" -> shift ? '>' : '.';
            case "SLASH" -> shift ? '?' : '/';
            case "GRAVE" -> shift ? '~' : '`';
            case "DECIMAL" -> '.';
            default -> 0;
        };
    }

    private static final class ProbeBrowser extends CefBrowserOsr {
        volatile int paintCount;
        ProbeBrowser(CefClient client, String url) {
            super(client, url, false, (CefRequestContext) null);
            browser_rect_.setBounds(0, 0, viewportWidth, viewportHeight);
        }
        void resizeViewport(int width, int height) {
            browser_rect_.setBounds(0, 0, width, height);
            wasResized(width, height);
        }
        void injectMove(int x, int y, boolean leftDown, boolean rightDown) {
            int mods = (leftDown ? CefMouseEvent.BUTTON1_MASK : 0)
                    | (rightDown ? CefMouseEvent.BUTTON3_MASK : 0);
            sendMouseEvent(new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, x, y, 0, 0, mods));
        }
        void injectButton(int x, int y, int button, boolean pressed) {
            int mods = button == 3 ? CefMouseEvent.BUTTON3_MASK : CefMouseEvent.BUTTON1_MASK;
            if (pressed) setFocus(true);
            int glfwAction = pressed ? 1 : 0;
            int glfwButton = button == 3 ? 2 : 0;
            sendMouseEvent(new CefMouseEvent(glfwAction, x, y, 1, glfwButton, mods));
            if (!pressed) injectMove(x, y, false, false);
        }
        void injectWheel(int x, int y, double delta) {
            sendMouseWheelEvent(new CefMouseWheelEvent(
                    CefMouseWheelEvent.WHEEL_UNIT_SCROLL, x, y, -delta, 0));
        }
        void injectKey(int glfwCode, char ch, int mods, boolean pressed) {
            // CinemaMod's Windows JNI reads CefKeyEvent.scancode to derive
            // a Windows virtual key. Without it, letter keys map to VK=0.
            long scancode = McefProbe.glfwScancode(glfwCode);
            CefKeyEvent key = new CefKeyEvent(
                    pressed ? CefKeyEvent.KEY_PRESS : CefKeyEvent.KEY_RELEASE,
                    glfwCode, ch, mods);
            key.scancode = scancode;
            sendKeyEvent(key);
            if (pressed && ch != 0 && (mods & (2 | 4)) == 0) {
                CefKeyEvent typed = new CefKeyEvent(CefKeyEvent.KEY_TYPE, ch, ch, mods);
                typed.scancode = scancode;
                sendKeyEvent(typed);
            }
        }
        @Override public Rectangle getViewRect(CefBrowser browser) { return browser_rect_; }
        @Override
        public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects,
                            ByteBuffer buffer, int width, int height) {
            if (popup || width <= 0 || height <= 0 || buffer == null) return;
            int count = ++paintCount;
            if (count <= 5 || count % 120 == 0) {
                System.out.println("[LiveWebMap-MCEF] PAINT #" + count + " " + width + "x" + height);
            }
            McefFrameUpload.acceptPaint(buffer, width, height);
        }
    }
}
