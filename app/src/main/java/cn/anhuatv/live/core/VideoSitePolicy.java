package cn.anhuatv.live.core;

import java.net.URI;
import cn.anhuatv.live.core.WebChannelRoutes.Site;

/** Website-only recovery; never extracts media addresses or changes native channels. */
public final class VideoSitePolicy {
    private VideoSitePolicy() {}

    public static boolean enabled(Site site) { return site == Site.MIGU || site == Site.YOUKU; }
    public static int desktopWidth(Site site) { return site == Site.MIGU ? 1920 : 1280; }

    public static String userAgent(String original, boolean desktop) {
        if (desktop) return WebPagePolicy.desktopUserAgent(original);
        if (original == null) return "";
        String value = original.replace("; wv", "").replaceAll("Version/[0-9.]+\\s*", "");
        if (!value.contains("Mobile") && value.contains("Safari/")) value = value.replace("Safari/", "Mobile Safari/");
        return value.trim();
    }

    /** Only mappings advertised by the providers; custom pages and login routes stay intact. */
    public static String page(Site site, String current, boolean desktop) {
        if (!enabled(site) || !WebPagePolicy.isSitePage(site, current)) return current;
        try {
            URI u = new URI(current);
            if (u.getPort() != -1 && u.getPort() != 443) return current;
            String host = u.getHost().toLowerCase(java.util.Locale.ROOT), path = u.getRawPath();
            String next = null, target = null;
            if (site == Site.MIGU && (host.equals("www.miguvideo.com") || host.equals("m.miguvideo.com") || host.equals("miguvideo.com"))) {
                if (path.matches("/[pm]/(?:detail|vertical)/[0-9]+/?")) {
                    next = path.replaceFirst("^/[pm]/", desktop ? "/p/" : "/m/");
                    target = desktop ? "www.miguvideo.com" : "m.miguvideo.com";
                } else if (path.matches("/p/live/[A-Za-z0-9_\\-]+/?") || path.matches("/m/live/home/[A-Za-z0-9_\\-]+/?")) {
                    String id = path.replaceFirst("^/(?:p/live/|m/live/home/)", "");
                    next = (desktop ? "/p/live/" : "/m/live/home/") + id;
                    target = desktop ? "www.miguvideo.com" : "m.miguvideo.com";
                } else if (path.isEmpty() || path.equals("/") || path.equals("/m/home/")) {
                    next = desktop ? "/" : "/m/home/";
                    target = desktop ? "www.miguvideo.com" : "m.miguvideo.com";
                }
            } else if (site == Site.YOUKU) {
                if ((host.equals("v.youku.com") || host.equals("m.youku.com")) && path.matches("/(?:v_show|video)/id_[A-Za-z0-9_=\\-]+\\.html")) {
                    next = path.replaceFirst("^/(?:v_show|video)/", desktop ? "/v_show/" : "/video/");
                    target = desktop ? "v.youku.com" : "m.youku.com";
                } else if ((host.equals("www.youku.com") || host.equals("m.youku.com")) && (path.isEmpty() || path.equals("/") || path.equals("/ku/webhome"))) {
                    next = desktop ? "/ku/webhome" : "/";
                    target = desktop ? "www.youku.com" : "m.youku.com";
                }
            }
            if (next == null) return current;
            return "https://" + target + next + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery()) +
                    (u.getRawFragment() == null ? "" : "#" + u.getRawFragment());
        } catch (Exception ignored) { return current; }
    }

    public static boolean isPlaybackPage(Site site, String url) {
        if (!enabled(site) || !WebPagePolicy.isSitePage(site, url)) return false;
        try {
            String p = new URI(url).getPath();
            return site == Site.MIGU ? p.matches("/[pm]/(?:detail|liveDetail|vertical)/.+") || p.startsWith("/p/live/") || p.startsWith("/m/live/home/") :
                    p.matches("/(?:v_show|video)/id_.+\\.html");
        } catch (Exception ignored) { return false; }
    }

    public static boolean protectedMediaOrigin(Site site, String topPage, String requestedOrigin) {
        if (!enabled(site) || !WebPagePolicy.isSitePage(site, topPage) || !WebPagePolicy.isSitePage(site, requestedOrigin)) return false;
        try {
            URI top = new URI(topPage), origin = new URI(requestedOrigin);
            return "https".equalsIgnoreCase(top.getScheme()) && "https".equalsIgnoreCase(origin.getScheme()) &&
                    top.getHost().equalsIgnoreCase(origin.getHost()) && port(top) == 443 && port(origin) == 443;
        } catch (Exception ignored) { return false; }
    }
    private static int port(URI u) { return u.getPort() == -1 ? 443 : u.getPort(); }
}
