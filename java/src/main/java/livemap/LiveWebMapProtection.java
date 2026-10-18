package livemap;

import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight protection for the single-tab, map-only embedded browser.
 * - No popups or new tabs (the UI has no tab manager).
 * - Top-level navigation stays on projectzomboidmap.com.
 * - Known advertising hostnames are blocked at request time.
 *
 * Not a replacement for the complete uBlock Origin filtering engine.
 */
public final class LiveWebMapProtection {
    private static final String MAP_HOST = "projectzomboidmap.com";
    private static final AtomicInteger BLOCKED_POPUPS = new AtomicInteger();
    private static final AtomicInteger BLOCKED_NAVIGATIONS = new AtomicInteger();
    private static final AtomicInteger BLOCKED_RESOURCES = new AtomicInteger();

    // Conservative network-only blocklist; no generic keywords or broad TLDs.
    // Can be supplemented without recompiling through the user-managed file below.
    private static final String[] BUILTIN_BLOCKED_HOSTS = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adnxs.com", "adsrvr.org", "amazon-adsystem.com", "casalemedia.com",
        "criteo.com", "criteo.net", "indexww.com", "media.net",
        "openx.net", "outbrain.com", "pubmatic.com", "rubiconproject.com",
        "smartadserver.com", "taboola.com", "yieldmo.com", "mgid.com",
        "revcontent.com", "propellerads.com", "popads.net", "popcash.net",
        "exoclick.com", "hilltopads.net", "clickadu.com", "adsterra.com",
        "trafficjunky.net", "bidswitch.net", "sharethrough.com",
        "lijit.com", "sovrn.com", "spotxchange.com", "triplelift.com"
    };

    private static volatile Set<String> blockedHosts = Set.of();

    private LiveWebMapProtection() { }

    /** Install on the client BEFORE creating its first browser. */
    public static void install(CefClient client) {
        blockedHosts = readBlockedHosts();
        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public boolean onBeforePopup(CefBrowser browser, CefFrame frame,
                                         String targetUrl, String targetFrameName) {
                noteBlock("popup", targetUrl, BLOCKED_POPUPS);
                return true; // Abort popup before a second browser is created.
            }
        });
        client.addRequestHandler(new CefRequestHandlerAdapter() {
            @Override
            public boolean onOpenURLFromTab(CefBrowser browser, CefFrame frame,
                                            String targetUrl, boolean userGesture) {
                noteBlock("new-tab", targetUrl, BLOCKED_POPUPS);
                return true; // No invisible background tabs.
            }

            @Override
            public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request,
                                          boolean userGesture, boolean isRedirect) {
                if (frame != null && !frame.isMain()) return false;
                String url = request == null ? null : request.getURL();
                if (isAllowedMapNavigation(url)) return false;
                noteBlock("off-map navigation", url, BLOCKED_NAVIGATIONS);
                return true;
            }

            @Override
            public CefResourceRequestHandler getResourceRequestHandler(
                    CefBrowser browser, CefFrame frame, CefRequest request,
                    boolean isNavigation, boolean isDownload, String requestInitiator,
                    BoolRef disableDefaultHandling) {
                // Navigation is handled separately so this only blocks known ad hosts.
                return RESOURCE_HANDLER;
            }
        });
        System.out.println("[LiveWebMap-MCEF] Popup protection ON; off-site navigation locked; "
                + blockedHosts.size() + " advertising domains configured");
    }

    private static final CefResourceRequestHandlerAdapter RESOURCE_HANDLER =
            new CefResourceRequestHandlerAdapter() {
                @Override
                public boolean onBeforeResourceLoad(CefBrowser browser, CefFrame frame,
                                                    CefRequest request) {
                    String url = request == null ? null : request.getURL();
                    String host = hostOf(url);
                    if (host == null || !matchesAny(host, blockedHosts)) return false;
                    noteBlock("ad resource", url, BLOCKED_RESOURCES);
                    return true;
                }
            };

    private static boolean isAllowedMapNavigation(String url) {
        // CEF may perform about:blank navigation when initializing the browser.
        if ("about:blank".equalsIgnoreCase(url)) return true;
        String host = hostOf(url);
        return url != null && url.regionMatches(true, 0, "https://", 0, 8)
                && host != null && matchesDomain(host, MAP_HOST);
    }

    private static String hostOf(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return null;
        try {
            URI uri = URI.create(rawUrl);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")))
                return null;
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return null;
            String normalized = host.toLowerCase(Locale.ROOT);
            return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean matchesDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static boolean matchesAny(String host, Set<String> domains) {
        for (String domain : domains) {
            if (matchesDomain(host, domain)) return true;
        }
        return false;
    }

    private static Set<String> readBlockedHosts() {
        Set<String> all = new HashSet<>();
        Collections.addAll(all, BUILTIN_BLOCKED_HOSTS);
        Path extra = Paths.get(System.getProperty("user.home"),
                "Zomboid", "LiveWebMap-blocked-domains.txt");
        if (Files.exists(extra)) {
            try {
                for (String raw : Files.readAllLines(extra, StandardCharsets.UTF_8)) {
                    String line = raw.trim().toLowerCase(Locale.ROOT);
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("*.")) line = line.substring(2);
                    // Domain names only, not URLs or generic adblock filter syntax.
                    if (!line.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+")) {
                        System.err.println("[LiveWebMap-MCEF] Ignoring invalid blocked domain entry");
                        continue;
                    }
                    all.add(line);
                }
            } catch (Exception e) {
                System.err.println("[LiveWebMap-MCEF] Extra domain list unavailable: " + e);
            }
        }
        return Set.copyOf(all);
    }

    private static void noteBlock(String kind, String url, AtomicInteger count) {
        int n = count.incrementAndGet();
        // Print hosts only, never URLs containing query parameters or tokens.
        if (n <= 12) {
            String host = hostOf(url);
            System.out.println("[LiveWebMap-MCEF] BLOCKED " + kind + " #" + n
                    + " (" + (host == null ? "no host" : host) + ")");
        } else if (n == 13) {
            System.out.println("[LiveWebMap-MCEF] Additional " + kind + " blocks suppressed in logs");
        }
    }
}
