package cn.anhuatv.live.core;

import cn.anhuatv.live.core.Models.Channel;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Explicit web entries are never passed to the native stream player. */
public final class WebChannelRoutes {
    public static final String ENTRY_ID = "CHENGDU_WEB";
    public static final String MIGU_ID = "MIGU_WEB", YOUKU_ID = "YOUKU_WEB";
    // The homepage shown in the user's screenshot contains the television live section.
    public static final String DEFAULT_URL = "https://cditv.cn/";
    private WebChannelRoutes() {}

    public enum Site {
        CHENGDU("chengdu", "成都电视台", "看度直播", DEFAULT_URL),
        MIGU("migu", "咪咕视频", "咪咕首页", "https://www.miguvideo.com/"),
        YOUKU("youku", "优酷视频", "优酷首页", "https://www.youku.com/ku/webhome");

        public final String key, title, homeButton, homeUrl;
        Site(String key, String title, String homeButton, String homeUrl) {
            this.key = key; this.title = title; this.homeButton = homeButton; this.homeUrl = homeUrl;
        }
        // Preserve the existing Chengdu preferences during an upgrade.
        public String urlPreference() { return this == CHENGDU ? "page_url" : "page_url_" + key; }
        public String desktopPreference() { return this == CHENGDU ? "desktop_mode" : "desktop_mode_" + key; }
        public static Site fromKey(String key) {
            for (Site site : values()) if (site.key.equals(key)) return site;
            return CHENGDU;
        }
    }

    public static boolean usesWeb(Channel channel) { return site(channel) != null; }

    public static Site site(Channel channel) {
        if (channel == null) return null;
        if (MIGU_ID.equals(channel.id)) return Site.MIGU;
        if (YOUKU_ID.equals(channel.id)) return Site.YOUKU;
        return isChengdu(channel) ? Site.CHENGDU : null;
    }

    private static boolean isChengdu(Channel channel) {
        if (channel == null) return false;
        if (ENTRY_ID.equals(channel.id)) return true;
        String id = Catalog.normalize(channel.id), name = Catalog.normalize(channel.name);
        if (id.matches("CDTV[1-8]") || name.matches("CDTV[1-8]")) return true;
        if (name.matches("成都(?:电视台)?[1-8一二三四五六七八](?:套|台|频道)?")) return true;
        name = name.replaceFirst("^成都电视台(?=.+)", "成都");
        return name.matches("成都(?:卫视|电视台|新闻综合(?:频道)?|经济资讯(?:服务)?(?:频道)?|都市生活(?:频道)?|影视文艺(?:频道)?|公共(?:频道)?|少儿(?:频道)?)");
    }

    public static List<Channel> withWebEntry(List<Channel> channels) {
        List<Channel> result = new ArrayList<>(channels);
        boolean hasChengdu = false, hasMigu = false, hasYouku = false;
        for (Channel c : channels) {
            Site s = site(c);
            if (s == Site.CHENGDU) hasChengdu = true;
            if (s == Site.MIGU) hasMigu = true;
            if (s == Site.YOUKU) hasYouku = true;
        }
        if (!hasChengdu) {
            int insert = 0;
            while (insert < result.size() && (result.get(insert).group.equals("央视") || result.get(insert).group.equals("卫视"))) insert++;
            result.add(insert, new Channel(ENTRY_ID, "成都电视台（网页）", "成都", Collections.emptyList()));
        }
        // Append the new entries, keeping all existing station numbers and their relative order.
        if (!hasMigu) result.add(new Channel(MIGU_ID, "咪咕视频（网页）", "其他", Collections.emptyList()));
        if (!hasYouku) result.add(new Channel(YOUKU_ID, "优酷视频（网页）", "其他", Collections.emptyList()));
        return result;
    }

    /** Editable pages must remain ordinary public HTTP(S) URLs, without embedded credentials. */
    public static boolean validPageUrl(String value) {
        if (value == null || value.length() > 4096 || !value.equals(value.trim())) return false;
        try {
            URI uri = new URI(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isEmpty() && uri.getRawUserInfo() == null
                    && uri.getPort() >= -1 && uri.getPort() <= 65535;
        } catch (Exception e) { return false; }
    }
}
