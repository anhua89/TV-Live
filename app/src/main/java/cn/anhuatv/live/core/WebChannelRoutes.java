package cn.anhuatv.live.core;

import cn.anhuatv.live.core.Models.Channel;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Only Chengdu TV uses a web page. It is never passed to the native stream player. */
public final class WebChannelRoutes {
    public static final String ENTRY_ID = "CHENGDU_WEB";
    // A shared landing page, not invented per-channel deep links. Select the CDTV channel on it.
    public static final String DEFAULT_URL = "https://www.cditv.cn/html/cdrtv/#live";
    private WebChannelRoutes() {}

    public static boolean usesWeb(Channel channel) {
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
        for (Channel c : channels) if (usesWeb(c)) return result;
        int insert = 0;
        while (insert < result.size() && (result.get(insert).group.equals("央视") || result.get(insert).group.equals("卫视"))) insert++;
        result.add(insert, new Channel(ENTRY_ID, "成都电视台（网页）", "成都", Collections.emptyList()));
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
