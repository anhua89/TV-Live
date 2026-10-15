package cn.anhuatv.live.core;

import cn.anhuatv.live.core.Models.Entry;
import cn.anhuatv.live.core.Models.Source;
import java.util.ArrayList;
import java.util.List;

/** Chooses validated network, cache, or bundled content without an Android dependency. */
public final class CatalogResolver {
    public static final String BEST_FAN_URL = "https://raw.githubusercontent.com/best-fan/iptv-sources/master/cn_all.m3u8";
    public enum Origin { NETWORK, CACHE, BUNDLED, NONE }
    public interface Read { String load() throws Exception; }
    public static final class Attempt {
        public final String label;
        public final Read read;
        public Attempt(String label, Read read) { this.label = label; this.read = read; }
    }
    public static final class Result {
        public final String text, label, warning;
        public final List<Entry> entries;
        public final Origin origin;
        Result(String text, String label, String warning, List<Entry> entries, Origin origin) {
            this.text = text; this.label = label; this.warning = warning; this.entries = entries; this.origin = origin;
        }
    }
    private CatalogResolver() {}
    public static boolean isDefaultBestFan(Source s) { return s.id.equals("best-fan") && s.url.equals(BEST_FAN_URL); }

    public static Result resolve(Source source, boolean localOnly, List<Attempt> network,
                                 Attempt cache, Attempt bundled) {
        List<Attempt> attempts = new ArrayList<>(); List<Origin> origins = new ArrayList<>();
        if (!localOnly) for (Attempt a : network) { attempts.add(a); origins.add(Origin.NETWORK); }
        if (cache != null) { attempts.add(cache); origins.add(Origin.CACHE); }
        if (bundled != null) { attempts.add(bundled); origins.add(Origin.BUNDLED); }
        String warning = "";
        for (int i = 0; i < attempts.size(); i++) {
            Attempt a = attempts.get(i);
            try {
                String text = a.read.load();
                if (text == null) throw new IllegalArgumentException("内容为空");
                List<Entry> entries = PlaylistParser.parse(text, source);
                if (entries.isEmpty()) throw new IllegalArgumentException("没有可解析的频道");
                return new Result(text, a.label, warning, entries, origins.get(i));
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) break;
                if (origins.get(i) == Origin.NETWORK) warning = "网络刷新未成功（" + a.label + "）";
                else if (warning.isEmpty()) warning = a.label + "不可用";
            }
        }
        return new Result("", "没有可用目录", warning, new ArrayList<>(), Origin.NONE);
    }
}
