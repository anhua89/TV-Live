package cn.anhuatv.live.core;

import cn.anhuatv.live.core.Models.Entry;
import cn.anhuatv.live.core.Models.Source;
import cn.anhuatv.live.core.Models.Stream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extended M3U and name,URL TXT. No Android dependencies; covered by unit tests. */
public final class PlaylistParser {
    private static final Pattern ATTR = Pattern.compile("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,]+))");
    private PlaylistParser() {}

    public static List<Entry> parse(String text, Source source) {
        List<Entry> result = new ArrayList<>();
        String name = "", tvg = "", group = "其他", catchup = "", template = "";
        Map<String, String> headers = new LinkedHashMap<>();
        for (String raw : text.replace("\uFEFF", "").split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF:")) {
                int comma = outsideQuotesComma(line);
                Map<String, String> attrs = attributes(comma < 0 ? line : line.substring(0, comma));
                name = comma >= 0 ? line.substring(comma + 1).trim() : attrs.get("tvg-name");
                if (name == null) name = "";
                tvg = value(attrs, "tvg-id");
                group = attrs.containsKey("group-title") ? attrs.get("group-title") : "其他";
                catchup = value(attrs, "catchup"); template = value(attrs, "catchup-source");
                headers = new LinkedHashMap<>();
            } else if (line.startsWith("#EXTGRP:")) {
                group = line.substring(8).trim();
            } else if (line.startsWith("#EXTVLCOPT:")) {
                String opt = line.substring(11);
                int equal = opt.indexOf('=');
                if (equal > 0) addHeader(headers, opt.substring(0, equal), opt.substring(equal + 1));
            } else if (line.startsWith("#EXTHTTP:")) {
                // Common flat JSON header declaration (no JSON dependency in the parser).
                Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(line.substring(9));
                while (m.find()) addHeader(headers, m.group(1), m.group(2).replace("\\/", "/"));
            } else if (!line.startsWith("#")) {
                String url = line;
                String rowName = name;
                if (!isPlayableUrl(url)) {
                    int comma = line.indexOf(',');
                    if (comma <= 0) continue;
                    rowName = line.substring(0, comma).trim(); url = line.substring(comma + 1).trim();
                    if (url.equalsIgnoreCase("#genre#")) { group = rowName; continue; }
                }
                Map<String, String> rowHeaders = new LinkedHashMap<>(headers);
                int pipe = url.indexOf('|');
                if (pipe >= 0) {
                    for (String part : url.substring(pipe + 1).split("&")) {
                        int eq = part.indexOf('=');
                        if (eq > 0) addHeader(rowHeaders, decode(part.substring(0, eq)), decode(part.substring(eq + 1)));
                    }
                    url = url.substring(0, pipe);
                }
                if (isPlayableUrl(url) && rowName != null && !rowName.isEmpty()) {
                    result.add(new Entry(rowName, tvg, new Stream(url, source, group, rowHeaders, catchup, template)));
                    if (result.size() >= 20000) break;
                }
                name = ""; tvg = ""; catchup = ""; template = ""; headers.clear();
            }
        }
        return result;
    }

    private static String value(Map<String, String> map, String key) {
        String v = map.get(key); return v == null ? "" : v;
    }
    public static boolean isPlayableUrl(String url) {
        String s = url.toLowerCase(Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("rtsp://");
    }
    private static String decode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
    private static void addHeader(Map<String, String> map, String key, String value) {
        String k = key.toLowerCase(Locale.ROOT);
        if (value.contains("\r") || value.contains("\n")) return;
        switch (k) {
            case "http-user-agent": case "user-agent": map.put("User-Agent", value); break;
            case "http-referrer": case "http-referer": case "referer": case "referrer": map.put("Referer", value); break;
            case "origin": map.put("Origin", value); break;
            case "cookie": map.put("Cookie", value); break;
            case "authorization": map.put("Authorization", value); break;
            default: break;
        }
    }
    private static Map<String, String> attributes(String line) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher m = ATTR.matcher(line);
        while (m.find()) map.put(m.group(1).toLowerCase(Locale.ROOT),
                m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4));
        return map;
    }
    private static int outsideQuotesComma(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '\'' || c == '"') && (quote == 0 || quote == c)) quote = quote == 0 ? c : 0;
            if (c == ',' && quote == 0) return i;
        }
        return -1;
    }
}
