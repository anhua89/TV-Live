package cn.anhuatv.live.core;

import cn.anhuatv.live.core.Models.Channel;
import cn.anhuatv.live.core.Models.Entry;
import cn.anhuatv.live.core.Models.Stream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Catalog {
    private static final Pattern CCTV = Pattern.compile("^(?:CCTV|央视)(\\d{1,2})([+＋]?)(?:.*)$");
    private Catalog() {}

    public static String normalize(String input) {
        String n = input.toUpperCase(Locale.ROOT).replace("PLUS", "+").replace('＋', '+')
                .replaceAll("[\\s_－-]", "")
                .replaceAll("[（(\\[]?(?:超高清|高清|标清|蓝光|FHD|UHD|HD|SD|HEVC|H265|1080P|720P)[）)\\]]?", "")
                .replace("中央电视台", "CCTV");
        if (n.startsWith("CCTV4K")) return "CCTV4K";
        if (n.startsWith("CCTV8K")) return "CCTV8K";
        if (n.startsWith("CCTV4") && (n.contains("欧洲") || n.contains("美洲")))
            return n.contains("欧洲") ? "CCTV4欧洲" : "CCTV4美洲";
        Matcher m = CCTV.matcher(n);
        if (m.matches()) return "CCTV" + Integer.parseInt(m.group(1)) + m.group(2);
        if (n.matches("CDTV\\d+.*")) {
            Matcher d = Pattern.compile("^CDTV(\\d+)").matcher(n);
            if (d.find()) return "CDTV" + Integer.parseInt(d.group(1));
        }
        // Conservative: don't merge unknown channels just because their tvg-id matches.
        return n;
    }

    public static List<Channel> merge(List<Entry> entries) {
        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        for (Entry e : entries) {
            String key = normalize(e.name);
            if (key.isEmpty()) continue;
            if (!groups.containsKey(key)) groups.put(key, new ArrayList<>());
            groups.get(key).add(e);
        }
        List<Channel> result = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> bucket : groups.entrySet()) {
            List<Entry> list = bucket.getValue();
            Collections.sort(list, (a, b) -> Integer.compare(a.stream.priority, b.stream.priority));
            Map<String, Stream> routes = new LinkedHashMap<>();
            for (Entry e : list) if (!routes.containsKey(e.stream.key())) routes.put(e.stream.key(), e.stream);
            String key = bucket.getKey();
            String label = key.startsWith("CCTV") || key.startsWith("CDTV") ? key : list.get(0).name;
            String group = key.startsWith("CCTV") ? "央视" :
                    key.startsWith("CDTV") || label.contains("成都") ? "成都" :
                    label.contains("卫视") || list.get(0).stream.group.contains("卫视") ? "卫视" : "其他";
            result.add(new Channel(key, label, group, new ArrayList<>(routes.values())));
        }
        Collections.sort(result, (a, b) -> {
            int g = Integer.compare(groupRank(a.group), groupRank(b.group));
            if (g != 0) return g;
            int n = Integer.compare(channelNumber(a.id), channelNumber(b.id));
            if (n != 0) return n;
            return a.name.compareTo(b.name);
        });
        return result;
    }
    private static int groupRank(String g) {
        return g.equals("央视") ? 0 : g.equals("卫视") ? 1 : g.equals("成都") ? 2 : 3;
    }
    private static int channelNumber(String s) {
        Matcher m = Pattern.compile("^(?:CCTV|CDTV)(\\d+)([+]?)$").matcher(s);
        return m.matches() ? Integer.parseInt(m.group(1)) * 2 + (m.group(2).isEmpty() ? 0 : 1) : 10000;
    }
}
