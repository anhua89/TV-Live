package cn.anhuatv.live.core;

import java.net.URI;
import java.util.Locale;

/** Pure URL/UA policies, independent from the Android WebView implementation. */
public final class WebPagePolicy {
    private WebPagePolicy() {}

    public static boolean isCditv(String value) {
        if (!WebChannelRoutes.validPageUrl(value)) return false;
        try {
            String host = new URI(value).getHost().toLowerCase(Locale.ROOT);
            return host.equals("cditv.cn") || host.endsWith(".cditv.cn");
        } catch (Exception e) { return false; }
    }

    public static boolean isHome(String value) {
        if (!isCditv(value)) return false;
        try {
            URI uri = new URI(value);
            String host = uri.getHost().toLowerCase(Locale.ROOT), path = uri.getPath();
            return (host.equals("cditv.cn") || host.equals("www.cditv.cn"))
                    && (path.isEmpty() || path.equals("/") || path.equals("/index.html"));
        } catch (Exception e) { return false; }
    }

    public static String startUrl(String saved) {
        if (!WebChannelRoutes.validPageUrl(saved)) return WebChannelRoutes.DEFAULT_URL;
        try {
            URI uri = new URI(saved);
            String host = uri.getHost().toLowerCase(Locale.ROOT), path = uri.getPath();
            if ((host.equals("cditv.cn") || host.equals("www.cditv.cn"))
                    && (path.equals("/html/cdrtv/") || path.equals("/html/cdrtv"))
                    && uri.getRawQuery() == null) return WebChannelRoutes.DEFAULT_URL;
        } catch (Exception ignored) { /* validated above */ }
        return saved;
    }

    /** Keep the real installed Chromium version; remove the phone and embedded-browser markers. */
    public static String desktopUserAgent(String original) {
        if (original == null) return "";
        return original.replaceAll("\\([^)]*Android[^)]*\\)", "(X11; Linux x86_64)")
                .replaceAll("(?:Version/[0-9.]+|Mobile|; wv)(?:\\s|$)", " ").replaceAll("\\s{2,}", " ").trim();
    }

    /** Never show token-bearing query strings in diagnostics. */
    public static String displayUrl(String value) {
        if (!WebChannelRoutes.validPageUrl(value)) return "";
        try {
            URI u = new URI(value);
            return new URI(u.getScheme(), null, u.getHost(), u.getPort(), u.getPath(), null, null).toString();
        } catch (Exception e) { return ""; }
    }
}
