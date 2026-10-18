package livemap;

import zombie.core.Core;
import zombie.core.textures.Texture;
import zombie.input.Mouse;
import zombie.ui.UIElement;
import zombie.ui.UIManager;
import zombie.ui.UIFont;

/** F7 map window: bounded dragging, minimize, maximize/restore, close. */
public final class LiveWebMapPanel extends UIElement {
    private static final double HEADER = 28.0;
    private static final double MIN_WIDTH = 320.0;
    private static final double MIN_CONTENT_HEIGHT = 240.0;
    private static final double HANDLE = 23.0;
    private static final double DEFAULT_X = 100.0;
    private static final double DEFAULT_Y = 100.0;
    private static final double DEFAULT_WIDTH = 800.0;
    private static final double DEFAULT_HEIGHT = HEADER + 600.0;
    private static final double BUTTON_WIDTH = 30.0;
    private static final double PERFORMANCE_WIDTH = 100.0;
    private static final double MENU_ROW_HEIGHT = 26.0;
    private static final double BUTTON_TOP = 2.0;
    private static final double BUTTON_BOTTOM = HEADER - 2.0;

    private static LiveWebMapPanel instance;
    // Diagnostic state: Viewpoint can replace the game's normal render pipeline.
    // A visible UIElement is not proof that render() is actually dispatched.
    private static volatile long openedAtNanos;
    private static volatile long lastRenderNanos;
    private static volatile int renderCallbacks;
    private static volatile boolean textureDrawLogged;
    private static long lastHealthCheckNanos;
    private static long lastHealthReportNanos;
    private static int healthChecks;
    private static int consecutiveMissingRegistration;
    private static int repairs;
    private static int renderExceptionReports;

    /** Called from a lightweight Lua OnTick hook even if CEF never starts. */
    public static void healthTick() {
        LiveWebMapPanel panel = instance;
        if (panel == null || !panel.isVisible()) return;
        long now = System.nanoTime();
        if (now - lastHealthCheckNanos < 2_000_000_000L) return;
        lastHealthCheckNanos = now;
        healthChecks++;

        try {
            java.util.List<?> entries = UIManager.getUI();
            boolean registered = entries != null && entries.contains(panel);
            int count = entries == null ? -1 : entries.size();
            if (!registered) {
                consecutiveMissingRegistration++;
                // AddUI may be deferred until the next UI tick; don't race it.
                if (consecutiveMissingRegistration >= 2 && repairs < 2) {
                    UIManager.AddUI(panel);
                    panel.bringToTop();
                    repairs++;
                    consecutiveMissingRegistration = 0;
                    System.out.println("[LiveWebMap-Viewpoint] UI panel missing from UIManager; "
                            + "re-added (attempt " + repairs + ")");
                }
            } else {
                consecutiveMissingRegistration = 0;
            }

            long secondsOpen = openedAtNanos == 0 ? 0 : (now - openedAtNanos) / 1_000_000_000L;
            long sinceRender = lastRenderNanos == 0 ? -1 : (now - lastRenderNanos) / 1_000_000_000L;
            boolean renderMissing = secondsOpen >= 5 &&
                    (lastRenderNanos == 0 || now - lastRenderNanos > 5_000_000_000L);
            if (healthChecks == 1 || healthChecks == 4 ||
                    (renderMissing && now - lastHealthReportNanos >= 15_000_000_000L)) {
                lastHealthReportNanos = now;
                System.out.println("[LiveWebMap-Viewpoint] UI health: registered=" + registered
                        + " uiCount=" + count + " visible=" + panel.isVisible()
                        + " bounds=" + (int) (double) panel.getX() + "," + (int) (double) panel.getY()
                        + "," + (int) (double) panel.getWidth() + "x" + (int) (double) panel.getHeight()
                        + " rendered=" + renderCallbacks + " lastRenderAgoSec=" + sinceRender
                        + " browserTexture=" + (McefFrameUpload.getTexture() != null));
                if (renderMissing && registered) {
                    System.out.println("[LiveWebMap-Viewpoint] UI is registered but render() "
                            + "is not being dispatched. Viewpoint/UI pipeline is the likely conflict.");
                } else if (renderCallbacks > 0 && McefFrameUpload.getTexture() != null) {
                    System.out.println("[LiveWebMap-Viewpoint] Panel renders and texture exists; "
                            + "if invisible, test Viewpoint's UI compositing/z-order.");
                }
            }
        } catch (Throwable error) {
            if (renderExceptionReports++ < 2) {
                System.err.println("[LiveWebMap-Viewpoint] UI health check failed: " + error);
                error.printStackTrace(System.err);
            }
        }
    }

    private enum Action { NONE, MOVE, RESIZE, WEB, MIN_BUTTON, MAX_BUTTON, CLOSE_BUTTON, REFRESH_BUTTON, PERFORMANCE_BUTTON, MENU_SELECTION }
    private Action action = Action.NONE;
    private boolean rightPressed;
    private boolean minimized;
    private boolean maximized;
    private boolean performanceMenuOpen;

    // Bounds to restore from maximize and minimize. Minimize keeps the logical
    // content size while displaying only the title bar.
    private double restoreX, restoreY, restoreWidth, restoreHeight;
    private double minimizedContentHeight;
    private int lastScreenWidth = -1, lastScreenHeight = -1;

    public LiveWebMapPanel() {
        super();
        resetGeometry();
        setVisible(false);
        setConsumeMouseEvents(true);
        setForceCursorVisible(true);
    }

    private static double screenWidth() {
        return Math.max(1.0, Core.getInstance().getScreenWidth());
    }
    private static double screenHeight() {
        return Math.max(HEADER, Core.getInstance().getScreenHeight());
    }
    private static double clamp(double n, double lo, double hi) {
        return Math.max(lo, Math.min(hi, n));
    }

    // Keep the ENTIRE visible panel inside the game viewport, not only its header.
    private void clampToScreen() {
        double sw = screenWidth(), sh = screenHeight();
        if (minimized) {
            setHeight(Math.min(HEADER, sh));
            setWidth(Math.min(getWidth(), sw));
            setX(clamp(getX(), 0, Math.max(0, sw - getWidth())));
            setY(clamp(getY(), 0, Math.max(0, sh - getHeight())));
        } else if (maximized) {
            setX(0); setY(0);
            setWidth(sw); setHeight(sh);
        } else {
            setWidth(Math.min(getWidth(), sw));
            setHeight(Math.min(getHeight(), sh));
            setX(clamp(getX(), 0, Math.max(0, sw - getWidth())));
            setY(clamp(getY(), 0, Math.max(0, sh - getHeight())));
        }
    }

    private void resetGeometry() {
        performanceMenuOpen = false;
        minimized = false;
        maximized = false;
        setX(DEFAULT_X); setY(DEFAULT_Y);
        setWidth(Math.min(DEFAULT_WIDTH, screenWidth()));
        setHeight(Math.min(DEFAULT_HEIGHT, screenHeight()));
        clampToScreen();
        lastScreenWidth = (int) screenWidth();
        lastScreenHeight = (int) screenHeight();
    }

    public static boolean isOpen() { return instance != null && instance.isVisible(); }

    /** Used by the pixel uploader to avoid wasting CPU on a minimized/closed map. */
    public static boolean isRenderActive() {
        LiveWebMapPanel panel = instance;
        return panel != null && panel.isVisible() && !panel.minimized;
    }

    /** F7 always reopens with default geometry, as requested in Phase 7. */
    public static void toggle() {
        if (instance == null) {
            instance = new LiveWebMapPanel();
            UIManager.AddUI(instance);
        }
        if (instance.isVisible()) instance.closePanel();
        else {
            instance.resetGeometry();
            McefProbe.clearTextFocus();
            instance.applyBrowserSize();
            instance.setVisible(true);
            instance.bringToTop();
            openedAtNanos = System.nanoTime();
            lastRenderNanos = 0;
            renderCallbacks = 0;
            textureDrawLogged = false;
            lastHealthCheckNanos = 0;
            lastHealthReportNanos = 0;
            healthChecks = 0;
            consecutiveMissingRegistration = 0;
            repairs = 0;
        }
        System.out.println("[LiveWebMap] F7 panel visible=" + instance.isVisible());
    }

    private void closePanel() {
        releaseBrowserButtons();
        performanceMenuOpen = false;
        action = Action.NONE;
        setCapture(false);
        McefProbe.clearTextFocus();
        setVisible(false);
    }

    /** Keep keyboard capture from remaining active when clicking elsewhere. */
    public static void pollOutsideClick() {
        LiveWebMapPanel panel = instance;
        if (panel == null || !panel.isVisible() || !Mouse.isLeftPressed()) return;
        double sx = UIManager.getLastMouseX(), sy = UIManager.getLastMouseY();
        if (sx < panel.getAbsoluteX() || sx >= panel.getAbsoluteX() + panel.getWidth()
                || sy < panel.getAbsoluteY() || sy >= panel.getAbsoluteY() + panel.getHeight()) {
            panel.performanceMenuOpen = false;
            if (McefProbe.isTextFocused()) McefProbe.clearTextFocus();
        }
    }

    private double contentHeight() { return Math.max(1.0, getHeight() - HEADER); }
    private boolean onWeb(double x, double y) {
        return !minimized && x >= 0 && x < getWidth() && y >= HEADER && y < getHeight();
    }
    private boolean onGrip(double x, double y) {
        return !minimized && !maximized && x >= getWidth() - HANDLE && x < getWidth()
                && y >= getHeight() - HANDLE && y < getHeight();
    }
    private int webX(double x) {
        int width = McefProbe.getViewportWidth();
        return Math.max(0, Math.min(width - 1,
                (int) Math.floor(x * width / Math.max(1.0, getWidth()))));
    }
    private int webY(double y) {
        int height = McefProbe.getViewportHeight();
        return Math.max(0, Math.min(height - 1,
                (int) Math.floor((y - HEADER) * height / contentHeight())));
    }
    private double cursorX() { return UIManager.getLastMouseX() - getAbsoluteX(); }
    private double cursorY() { return UIManager.getLastMouseY() - getAbsoluteY(); }

    private void releaseBrowserButtons() {
        if (action == Action.WEB) {
            McefProbe.mouseButton(webX(cursorX()), webY(cursorY()), 1, false);
        }
        if (rightPressed) {
            McefProbe.mouseButton(webX(cursorX()), webY(cursorY()), 3, false);
            rightPressed = false;
        }
    }
    private void applyBrowserSize() {
        if (!minimized) {
            int[] pixels = LiveWebMapPerformance.viewport(getWidth(), contentHeight());
            McefProbe.setViewportSize(pixels[0], pixels[1]);
        }
    }

    // Four standard controls and a 94px render preset selector.
    private double performanceStart() { return getWidth() - 4.0 * BUTTON_WIDTH - PERFORMANCE_WIDTH; }
    private double buttonsStart() { return getWidth() - 3.0 * BUTTON_WIDTH; }
    private Action hitButton(double x, double y) {
        if (y < BUTTON_TOP || y >= BUTTON_BOTTOM || x < performanceStart() || x >= getWidth())
            return Action.NONE;
        if (x < performanceStart() + PERFORMANCE_WIDTH) return Action.PERFORMANCE_BUTTON;
        if (x < buttonsStart()) return Action.REFRESH_BUTTON;
        if (x < getWidth() - 2 * BUTTON_WIDTH) return Action.MIN_BUTTON;
        if (x < getWidth() - BUTTON_WIDTH) return Action.MAX_BUTTON;
        return Action.CLOSE_BUTTON;
    }

    private int menuItem(double x, double y) {
        if (!performanceMenuOpen || minimized || x < performanceStart() ||
                x >= performanceStart() + PERFORMANCE_WIDTH || y < HEADER) return -1;
        int result = (int) Math.floor((y - HEADER) / MENU_ROW_HEIGHT);
        return result >= 0 && result < LiveWebMapPerformance.count() ? result : -1;
    }

    private void minimizeOrRestore() {
        releaseBrowserButtons();
        performanceMenuOpen = false;
        McefProbe.clearTextFocus();
        if (!minimized) {
            minimizedContentHeight = getHeight();
            minimized = true;
            setHeight(Math.min(HEADER, screenHeight()));
            clampToScreen();
        } else {
            minimized = false;
            setHeight(minimizedContentHeight);
            clampToScreen();
            applyBrowserSize();
        }
    }

    private void maximizeOrRestore() {
        releaseBrowserButtons();
        performanceMenuOpen = false;
        McefProbe.clearTextFocus();
        if (maximized) {
            maximized = false;
            minimized = false;
            setX(restoreX); setY(restoreY);
            setWidth(restoreWidth); setHeight(restoreHeight);
            clampToScreen();
        } else {
            // If currently minimized, restore content before saving geometry.
            if (minimized) {
                minimized = false;
                setHeight(minimizedContentHeight);
                clampToScreen();
            }
            restoreX = getX(); restoreY = getY();
            restoreWidth = getWidth(); restoreHeight = getHeight();
            maximized = true;
            clampToScreen();
        }
        applyBrowserSize();
    }

    @Override
    public Boolean onMouseDown(double x, double y) {
        if (!isVisible()) return false;
        bringToTop();
        int preset = menuItem(x, y);
        if (preset >= 0) {
            performanceMenuOpen = false;
            McefProbe.clearTextFocus();
            LiveWebMapPerformance.select(preset);
            applyBrowserSize();
            action = Action.MENU_SELECTION;
            setCapture(true);
            return true;
        }
        Action clickedButton = hitButton(x, y);
        if (clickedButton != Action.NONE) {
            McefProbe.clearTextFocus();
            performanceMenuOpen = clickedButton == Action.PERFORMANCE_BUTTON && !minimized
                    && !performanceMenuOpen;
            action = clickedButton;
        } else if (performanceMenuOpen) {
            performanceMenuOpen = false;
            return true;
        } else if (onGrip(x, y)) {
            McefProbe.clearTextFocus();
            action = Action.RESIZE;
        } else if (y >= 0 && y < HEADER) {
            McefProbe.clearTextFocus();
            action = Action.MOVE;
        } else if (onWeb(x, y)) {
            McefProbe.suspendTextCapture();
            action = Action.WEB;
            McefProbe.mouseButton(webX(x), webY(y), 1, true);
        } else return false;
        setCapture(true);
        return true;
    }

    @Override
    public Boolean onMouseUp(double x, double y) {
        Action released = action;
        action = Action.NONE;
        setCapture(false);
        if (released == Action.WEB) {
            McefProbe.mouseButton(webX(x), webY(y), 1, false);
            McefProbe.probeTextFocus();
        } else if (released == Action.RESIZE) {
            clampToScreen();
            applyBrowserSize();
        } else if (released == hitButton(x, y)) {
            if (released == Action.MIN_BUTTON) minimizeOrRestore();
            else if (released == Action.MAX_BUTTON) maximizeOrRestore();
            else if (released == Action.CLOSE_BUTTON) closePanel();
            else if (released == Action.REFRESH_BUTTON) {
                performanceMenuOpen = false;
                McefProbe.reloadMap();
            }
        }
        return true;
    }

    @Override
    public void onMouseUpOutside(double x, double y) {
        if (action == Action.RESIZE) {
            clampToScreen();
            applyBrowserSize();
        }
        releaseBrowserButtons();
        action = Action.NONE;
        setCapture(false);
    }

    @Override
    public Boolean onRightMouseDown(double x, double y) {
        if (!isVisible() || !onWeb(x, y)) return false;
        bringToTop();
        McefProbe.suspendTextCapture();
        rightPressed = true;
        McefProbe.mouseButton(webX(x), webY(y), 3, true);
        return true;
    }

    @Override
    public Boolean onRightMouseUp(double x, double y) {
        if (!rightPressed) return false;
        McefProbe.mouseButton(webX(x), webY(y), 3, false);
        McefProbe.probeTextFocus();
        rightPressed = false;
        return true;
    }

    private void pointerMotion(double dx, double dy) {
        if (action == Action.MOVE) {
            if (!maximized) {
                setX(getX() + dx);
                setY(getY() + dy);
                clampToScreen();
            }
        } else if (action == Action.RESIZE) {
            double maxW = Math.max(1, Math.min(3840.0, screenWidth() - getX()));
            double maxH = Math.max(HEADER, Math.min(HEADER + 2160.0, screenHeight() - getY()));
            setWidth(clamp(getWidth() + dx, Math.min(MIN_WIDTH, maxW), maxW));
            setHeight(clamp(getHeight() + dy,
                    Math.min(HEADER + MIN_CONTENT_HEIGHT, maxH), maxH));
            clampToScreen();
        } else if (action == Action.NONE || action == Action.WEB) {
            double x = cursorX(), y = cursorY();
            if (onWeb(x, y) || action == Action.WEB) {
                McefProbe.mouseMove(webX(x), webY(y), action == Action.WEB, rightPressed);
            }
        }
    }

    @Override
    public Boolean onMouseMove(double dx, double dy) {
        if (!isVisible()) return false;
        pointerMotion(dx, dy);
        return true;
    }
    @Override
    public void onMouseMoveOutside(double dx, double dy) {
        if (isVisible() && action != Action.NONE) pointerMotion(dx, dy);
    }
    @Override
    public Boolean onMouseWheel(double delta) {
        if (!isVisible() || minimized || !isMouseOver()) return false;
        double x = cursorX(), y = cursorY();
        if (menuItem(x, y) >= 0) return true;
        if (!onWeb(x, y) || onGrip(x, y)) return false;
        McefProbe.mouseWheel(webX(x), webY(y), delta);
        return true;
    }

    // The Lua keyboard sink continues to handle text-focused typing.

    /** Standard filled downward caret; no font glyph or new texture needed. */
    private void drawDropdownTriangle(double cx, double cy) {
        // Six horizontal scan lines form a filled 11px-wide downward triangle.
        for (int row = 0; row < 6; row++) {
            double half = 5.0 - row;
            double y = cy - 2.5 + row;
            DrawLine(null, cx - half, y, cx + half, y,
                    1.2f, 0.96, 0.97, 1.0, 0.95);
        }
    }

    /** Draw the refresh glyph with vector lines so no additional UI textures are needed. */
    private void drawRefreshIcon(double cx, double cy) {
        double radius = 5.4;
        double previous = -2.25;
        for (int i = 1; i <= 9; i++) {
            double angle = -2.25 + i * (4.75 / 9.0);
            DrawLine(null, cx + radius * Math.cos(previous),
                    cy + radius * Math.sin(previous),
                    cx + radius * Math.cos(angle), cy + radius * Math.sin(angle),
                    1.5f, 1, 1, 1, 0.95);
            previous = angle;
        }
        DrawLine(null, cx - 1, cy - 7, cx + 5, cy - 6, 1.5f, 1, 1, 1, 0.95);
        DrawLine(null, cx + 5, cy - 6, cx + 4, cy, 1.5f, 1, 1, 1, 0.95);
    }

    private void drawButton(Action button, double bx, double w) {
        double cy = HEADER / 2.0;
        boolean hover = hitButton(cursorX(), cursorY()) == button;
        if (hover) {
            if (button == Action.CLOSE_BUTTON) {
                DrawTextureScaledCol(null, bx, BUTTON_TOP, w, BUTTON_BOTTOM - BUTTON_TOP,
                        0.60, 0.18, 0.19, 0.95);
            } else {
                DrawTextureScaledCol(null, bx, BUTTON_TOP, w, BUTTON_BOTTOM - BUTTON_TOP,
                        0.25, 0.29, 0.36, 0.95);
            }
        }
        double cx = bx + w / 2.0;
        if (button == Action.MIN_BUTTON) {
            // Minimize and restore-from-minimize both use the standard dash.
            DrawLine(null, cx - 5, cy + 4, cx + 5, cy + 4, 1.6f, 1, 1, 1, 0.95);
        } else if (button == Action.MAX_BUTTON) {
            if (maximized && !minimized) {
                // Overlapping squares indicate restore.
                drawSquare(cx - 3, cy - 5, 9, 0.95);
                drawSquare(cx - 6, cy - 2, 9, 0.95);
            } else {
                drawSquare(cx - 5, cy - 5, 10, 0.95);
            }
        } else if (button == Action.REFRESH_BUTTON) {
            drawRefreshIcon(cx, cy);
        } else if (button == Action.CLOSE_BUTTON) {
            DrawLine(null, cx - 4, cy - 4, cx + 4, cy + 4, 1.5f, 1, 1, 1, 0.95);
            DrawLine(null, cx - 4, cy + 4, cx + 4, cy - 4, 1.5f, 1, 1, 1, 0.95);
        }
    }
    private void drawSquare(double x, double y, double size, double a) {
        DrawLine(null, x, y, x + size, y, 1.2f, 1, 1, 1, a);
        DrawLine(null, x + size, y, x + size, y + size, 1.2f, 1, 1, 1, a);
        DrawLine(null, x + size, y + size, x, y + size, 1.2f, 1, 1, 1, a);
        DrawLine(null, x, y + size, x, y, 1.2f, 1, 1, 1, a);
    }

    @Override
    public void render() {
        if (!isVisible()) return;
        lastRenderNanos = System.nanoTime();
        int callbacks = ++renderCallbacks;
        if (callbacks == 1) {
            System.out.println("[LiveWebMap-Viewpoint] First UI render callback; bounds="
                    + (int) (double) getX() + "," + (int) (double) getY() + ","
                    + (int) (double) getWidth() + "x" + (int) (double) getHeight());
        }
        // Game resolution/UI scaling may change independently of dragging.
        int sw = (int) screenWidth(), sh = (int) screenHeight();
        if (sw != lastScreenWidth || sh != lastScreenHeight) {
            lastScreenWidth = sw; lastScreenHeight = sh;
            double oldW = getWidth(), oldH = getHeight();
            clampToScreen();
            if (!minimized && (oldW != getWidth() || oldH != getHeight())) applyBrowserSize();
        }

        super.render();
        double w = getWidth(), h = getHeight();
        DrawTextureScaledCol(null, 0, 0, w, HEADER, 0.11, 0.13, 0.17, 0.96);
        DrawText(UIFont.Small, "LiveWebMap", 10, 6, 1, 1, 1, 1);
        if (w >= 570 && !minimized) {
            String label = McefProbe.getViewportWidth() + "x" + McefProbe.getViewportHeight()
                    + (McefProbe.isTextFocused() ? "  TYPING" : "  AUTO");
            DrawText(UIFont.Small, label, performanceStart() - 155,
                    6, 0.83, 0.95, 1, 1);
        }
        double perfX = performanceStart();
        DrawTextureScaledCol(null, perfX, BUTTON_TOP, PERFORMANCE_WIDTH,
                BUTTON_BOTTOM - BUTTON_TOP, 0.19, 0.24, 0.31, 0.92);
        DrawText(UIFont.Small, LiveWebMapPerformance.currentName(),
                perfX + 8, 6, 0.96, 0.97, 1, 1);
        drawDropdownTriangle(perfX + PERFORMANCE_WIDTH - 14, HEADER / 2.0);
        drawButton(Action.REFRESH_BUTTON, buttonsStart() - BUTTON_WIDTH, BUTTON_WIDTH);
        drawButton(Action.MIN_BUTTON, w - 3 * BUTTON_WIDTH, BUTTON_WIDTH);
        drawButton(Action.MAX_BUTTON, w - 2 * BUTTON_WIDTH, BUTTON_WIDTH);
        drawButton(Action.CLOSE_BUTTON, w - BUTTON_WIDTH, BUTTON_WIDTH);

        if (minimized) return;
        Texture texture = McefFrameUpload.getTexture();
        if (texture != null) {
            if (!textureDrawLogged) {
                textureDrawLogged = true;
                System.out.println("[LiveWebMap-Viewpoint] First Chromium texture drawn by UIElement");
            }
            DrawTextureScaled(texture, 0, HEADER, w, h - HEADER, 1);
        }
        else DrawText(UIFont.Small, "Waiting for Chromium...", 10, HEADER + 10, 1, 1, 1, 1);

        if (!maximized) {
            DrawTextureScaledCol(null, w - HANDLE, h - HANDLE, HANDLE, HANDLE,
                    0.12, 0.14, 0.17, 0.70);
            for (int i = 0; i <= 11; i++) {
                double y = h - 8 - i;
                DrawLine(null, w - 20 + i, y, w - 8, y,
                        1.3f, 0.84, 0.89, 0.96, 0.95);
            }
        }

        // Draw after the webpage so the dropdown stays in front of Chromium.
        if (performanceMenuOpen) {
            double x = performanceStart();
            for (int i = 0; i < LiveWebMapPerformance.count(); i++) {
                double y = HEADER + i * MENU_ROW_HEIGHT;
                boolean active = i == LiveWebMapPerformance.selectedIndex();
                boolean hovered = menuItem(cursorX(), cursorY()) == i;
                DrawTextureScaledCol(null, x, y, PERFORMANCE_WIDTH, MENU_ROW_HEIGHT,
                        active || hovered ? 0.26 : 0.12,
                        active || hovered ? 0.36 : 0.16,
                        active || hovered ? 0.45 : 0.22, 0.98);
                DrawText(UIFont.Small, LiveWebMapPerformance.descriptionAt(i),
                        x + 6, y + 4, 1, 1, 1, 1);
            }
        }
    }
}
