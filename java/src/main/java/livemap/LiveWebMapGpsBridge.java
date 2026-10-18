package livemap;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.network.CefRequest.TransitionType;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Direct in-game GPS feed for the website's *existing* GPS marker.
 *
 * The player location is passed from client-side Project Zomboid Lua, not
 * read from GPS Companion or any stale file on disk. gps-bridge.js exposes
 * the location as virtual Zomboid/Lua/pzm_live/live.txt in Chromium's OPFS.
 */
public final class LiveWebMapGpsBridge {
    private static final String PREFIX = "[LiveWebMap-GPS] ";
    private static final long POLL_INTERVAL_NS = 750_000_000L;
    private static final long INSTALL_INTERVAL_NS = 2_000_000_000L;
    private static final long STALE_INTERVAL_NS = 3_500_000_000L;
    private static final long NEW_SESSION_AFTER_NS = 5_000_000_000L;

    private static CefMessageRouter router;
    private static String installerScript;
    private static volatile boolean websiteReady;
    private static volatile boolean bridgeReady;
    private static volatile Sample latestSample;
    private static long lastInstallTime;
    private static long lastPollTime;
    private static boolean lastSentMissing;
    private static String sessionId = nextSessionId();
    private static String activeCharacter;
    private static int logCount;
    private static int syncCount;

    private LiveWebMapGpsBridge() {}

    private record Sample(String name, double x, double y, int z,
                          double fx, double fy, long receivedAtNs) {}

    private static String nextSessionId() {
        return System.currentTimeMillis() + "-" +
                Math.floorMod(System.nanoTime(), 1_000_000L);
    }

    /** Called from an @LuaMethod in McefProbe on the client Lua OnTick event. */
    public static void samplePlayer(String name, double x, double y, double z,
                                    double fx, double fy) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(fx) || !Double.isFinite(fy)
                || Math.abs(x) > 1_000_000 || Math.abs(y) > 1_000_000
                || Math.abs(z) > 100) return;
        String cleanName = name == null ? "Survivor" : name.trim();
        if (cleanName.isEmpty()) cleanName = "Survivor";
        if (cleanName.length() > 80) cleanName = cleanName.substring(0, 80);
        long now = System.nanoTime();
        Sample old = latestSample;
        if (old == null || now - old.receivedAtNs() > NEW_SESSION_AFTER_NS
                || !cleanName.equals(activeCharacter)) {
            sessionId = nextSessionId();
            activeCharacter = cleanName;
            System.out.println(PREFIX + "Now tracking in-game character: " + cleanName);
        }
        // Keep facing direction normalized. Both components are exactly what
        // GPS Companion's file format calls fx/fy; 0,1 is the safe fallback.
        double length = Math.hypot(fx, fy);
        if (length > 0.00001) {
            fx /= length;
            fy /= length;
        } else {
            fx = 0.0;
            fy = 1.0;
        }
        latestSample = new Sample(cleanName, x, y, (int) Math.round(z), fx, fy, now);
    }

    private static boolean correctSite(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && (host.equalsIgnoreCase("projectzomboidmap.com")
                        || host.equalsIgnoreCase("www.projectzomboidmap.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String getInstallerScript() throws Exception {
        if (installerScript == null) {
            try (InputStream input = LiveWebMapGpsBridge.class.getResourceAsStream(
                    "/livemap/gps-bridge.js")) {
                if (input == null) throw new IllegalStateException(
                        "Missing /livemap/gps-bridge.js in JAR");
                installerScript = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return installerScript;
    }

    public static void install(CefClient client) {
        try {
            getInstallerScript();
            router = CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig(
                    "liveWebMapGpsQuery", "liveWebMapGpsCancel"));
            if (router == null) throw new IllegalStateException("CEF GPS message router unavailable");
            router.addHandler(new CefMessageRouterHandlerAdapter() {
                @Override
                public boolean onQuery(CefBrowser origin, CefFrame frame, long queryId,
                                       String request, boolean persistent, CefQueryCallback callback) {
                    if (request == null || !request.startsWith("LWM_GPS:")) return false;
                    if (!correctSite(origin.getURL()) || !frame.isMain()) {
                        callback.failure(403, "Disallowed origin");
                        return true;
                    }
                    String status = request.substring("LWM_GPS:".length());
                    if (status.startsWith("READY:")) {
                        bridgeReady = true;
                        if (logCount++ < 25)
                            System.out.println(PREFIX + "Browser folder bridge ready: " + status);
                    } else if (status.startsWith("PICKED:")) {
                        System.out.println(PREFIX + "Website selected virtual Zomboid folder: " + status);
                    } else if (status.startsWith("ERROR:")) {
                        if (logCount++ < 25) System.err.println(PREFIX + "Browser JS: " + status);
                    } else if (status.startsWith("WRITE:")) {
                        if (syncCount++ < 5) System.out.println(PREFIX + "Browser virtual live.txt updated");
                    }
                    callback.success("ok");
                    return true;
                }
            }, true);
            client.addMessageRouter(router);
            client.addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadStart(CefBrowser browser, CefFrame frame, TransitionType transitionType) {
                    if (frame.isMain()) {
                        websiteReady = false;
                        bridgeReady = false;
                        lastSentMissing = false;
                        lastInstallTime = 0;
                    }
                }
                @Override
                public void onLoadEnd(CefBrowser browser, CefFrame frame, int status) {
                    if (frame.isMain() && correctSite(frame.getURL())) {
                        websiteReady = true;
                        bridgeReady = false;
                        lastInstallTime = 0;
                    }
                }
            });
            System.out.println(PREFIX + "Direct game GPS installed; no GPS Companion mod or live.txt needed");
        } catch (Throwable e) {
            System.err.println(PREFIX + "Could not initialize direct GPS bridge: " + e);
        }
    }

    /** Called from existing Java CEF browser tick. */
    public static void tick(CefBrowser browser) {
        if (browser == null || !websiteReady || !correctSite(browser.getURL())) return;
        long now = System.nanoTime();
        if (!bridgeReady) {
            if (now - lastInstallTime < INSTALL_INTERVAL_NS) return;
            lastInstallTime = now;
            try {
                browser.executeJavaScript(getInstallerScript(), browser.getURL(), 1);
            } catch (Throwable e) {
                if (logCount++ < 25) System.err.println(PREFIX + "JS installation failed: " + e);
            }
            return;
        }
        if (now - lastPollTime < POLL_INTERVAL_NS) return;
        lastPollTime = now;
        Sample current = latestSample;
        try {
            if (current == null || now - current.receivedAtNs() > STALE_INTERVAL_NS) {
                if (!lastSentMissing) {
                    browser.executeJavaScript("window.__lwmGpsPush(null);", browser.getURL(), 1);
                    lastSentMissing = true;
                    if (logCount++ < 25)
                        System.out.println(PREFIX + "No active player position; virtual GPS cleared");
                }
                return;
            }
            // Generate a new timestamp every send, INCLUDING while stationary.
            // The website marks old GPS timestamps grey, even when x/y have not changed.
            String json = formatGps(current, sessionId, System.currentTimeMillis());
            String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            browser.executeJavaScript("window.__lwmGpsPush('" + base64 + "');", browser.getURL(), 1);
            lastSentMissing = false;
            if (syncCount++ < 8) {
                System.out.println(PREFIX + "LIVE " + current.name() + " at "
                        + String.format(Locale.ROOT, "%.1f,%.1f", current.x(), current.y())
                        + " / floor " + current.z() + " (fresh timestamp)");
            }
        } catch (Exception e) {
            if (logCount++ < 15) System.err.println(PREFIX + "Live GPS update failed: " + e);
        }
    }

    static String formatGps(Sample current, String session, long epochMillis) {
        return String.format(Locale.ROOT,
                "{\"v\":1,\"session\":\"%s\",\"t\":%d,\"name\":\"%s\","
              + "\"x\":%.1f,\"y\":%.1f,\"z\":%d,\"fx\":%.2f,\"fy\":%.2f}",
                jsonEscape(session), epochMillis, jsonEscape(current.name()), current.x(),
                current.y(), current.z(), current.fx(), current.fy());
    }

    private static String jsonEscape(String input) {
        StringBuilder result = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 32) result.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                    else result.append(c);
                }
            }
        }
        return result.toString();
    }
}
