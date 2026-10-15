package cn.anhuatv.live;

import android.content.Context;
import cn.anhuatv.live.core.Catalog;
import cn.anhuatv.live.core.CatalogResolver;
import cn.anhuatv.live.core.WebChannelRoutes;
import cn.anhuatv.live.core.Models.Channel;
import cn.anhuatv.live.core.Models.Entry;
import cn.anhuatv.live.core.Models.Source;
import cn.anhuatv.live.core.PlaylistParser;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ChannelRepository {
    public static final class Result {
        public final List<Channel> channels;
        public final String summary;
        Result(List<Channel> channels, String summary) { this.channels = channels; this.summary = summary; }
    }
    private static final class Loaded {
        final List<Entry> entries; final String message;
        Loaded(List<Entry> entries, String message) { this.entries = entries; this.message = message; }
    }
    private final Context context;
    public ChannelRepository(Context context) { this.context = context.getApplicationContext(); }

    /** cacheOnly startup lets the last station play before remote subscriptions refresh. */
    public Result load(List<Source> sources, boolean cacheOnly) throws Exception {
        List<Source> enabled = new ArrayList<>();
        for (Source s : sources) if (s.enabled) enabled.add(s);
        if (enabled.isEmpty()) return new Result(WebChannelRoutes.withWebEntry(new ArrayList<>()), "尚未启用直播订阅；已保留成都网页入口。右键 → 订阅管理");
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(3, enabled.size()));
        try {
            List<Callable<Loaded>> calls = new ArrayList<>();
            for (Source s : enabled) calls.add(() -> loadOne(s, cacheOnly));
            List<Future<Loaded>> futures = pool.invokeAll(calls, cacheOnly ? 5 : 22, TimeUnit.SECONDS);
            List<Entry> all = new ArrayList<>(); StringBuilder notes = new StringBuilder();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    Loaded got = futures.get(i).get(); all.addAll(got.entries);
                    notes.append(got.message).append('\n');
                } catch (Exception e) {
                    // A slow provider must not evict its previously valid cached list.
                    Loaded cached = loadOne(enabled.get(i), true); all.addAll(cached.entries);
                    notes.append(enabled.get(i).name).append("：刷新超时，缓存 ").append(cached.entries.size()).append(" 条\n");
                }
            }
            List<Channel> channels = WebChannelRoutes.withWebEntry(Catalog.merge(all));
            return new Result(channels, channels.size() + " 个频道\n" + notes);
        } finally { pool.shutdownNow(); }
    }

    private Loaded loadOne(Source source, boolean cacheOnly) {
        File cache = new File(context.getFilesDir(), "lists/" + Store.digest(source.id + "|" + source.url) + ".m3u");
        if (source.url.startsWith("http://") || source.url.startsWith("https://")) {
            return loadNetworkSource(source, cacheOnly, cache);
        }
        String text = null; String note = "";
        try {
            if (source.url.startsWith("local://")) {
                String fileName = source.url.substring(8);
                if (!fileName.matches("[A-Za-z0-9-]+\\.m3u")) throw new IllegalArgumentException("无效的导入文件名");
                try (InputStream in = new FileInputStream(new File(context.getFilesDir(), "imports/" + fileName))) {
                    text = Net.decode(Net.read(in, 8 * 1024 * 1024));
                }
            } else if (source.url.startsWith("asset://")) {
                String asset = source.url.substring(8);
                if (!asset.equals("cdtv_candidate.m3u")) throw new IllegalArgumentException("未知内置候选");
                try (InputStream in = context.getAssets().open(asset)) { text = Net.decode(Net.read(in, 8 * 1024 * 1024)); }
            } else if (!cacheOnly) {
                text = Net.text(source.url, Net.noHeaders(), 8 * 1024 * 1024, false);
                if (PlaylistParser.parse(text, source).isEmpty()) throw new IllegalArgumentException("订阅中没有可解析的频道");
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                File dir = cache.getParentFile(); if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("缓存目录不可写");
                File tmp = new File(cache.getPath() + "." + java.util.UUID.randomUUID() + ".tmp");
                try {
                    try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
                    if (!tmp.renameTo(cache)) throw new java.io.IOException("缓存保存失败");
                } finally { if (tmp.exists()) tmp.delete(); }
            }
        } catch (Exception e) { text = null; note = "刷新失败（" + e.getMessage() + "）"; }
        if (text == null && cache.isFile()) {
            try (InputStream in = new FileInputStream(cache)) {
                text = Net.decode(Net.read(in, 8 * 1024 * 1024)); note += "，使用本地缓存";
            } catch (Exception e) { note += "，缓存不可读"; }
        }
        List<Entry> entries = text == null ? new ArrayList<>() : PlaylistParser.parse(text, source);
        return new Loaded(entries, source.name + "：" + entries.size() + " 条线路 " + note);
    }

    private Loaded loadNetworkSource(Source source, boolean cacheOnly, File cache) {
        List<CatalogResolver.Attempt> network = new ArrayList<>();
        network.add(new CatalogResolver.Attempt("原始订阅", () -> Net.text(source.url, Net.noHeaders(), 8 * 1024 * 1024, false)));
        boolean builtIn = CatalogResolver.isDefaultBestFan(source);
        if (builtIn && !BuildConfig.UPDATE_REPOSITORY.isEmpty()) {
            String mirror = "https://github.com/" + BuildConfig.UPDATE_REPOSITORY + "/releases/latest/download/channels.m3u";
            network.add(new CatalogResolver.Attempt("自己的发布目录", () -> Net.text(mirror, Net.noHeaders(), 8 * 1024 * 1024, true)));
        }
        CatalogResolver.Attempt cached = !cache.isFile() ? null : new CatalogResolver.Attempt("本地缓存", () -> {
            try (InputStream in = new FileInputStream(cache)) { return Net.decode(Net.read(in, 8 * 1024 * 1024)); }
        });
        CatalogResolver.Attempt seed = !builtIn ? null : new CatalogResolver.Attempt("内置 best-fan 目录", () -> {
            try (InputStream in = context.getAssets().open("best_fan_seed.m3u")) { return Net.decode(Net.read(in, 8 * 1024 * 1024)); }
        });
        CatalogResolver.Result result = CatalogResolver.resolve(source, cacheOnly, network, cached, seed);
        String note = result.warning.isEmpty() ? "" : result.warning + "；";
        if (result.origin == CatalogResolver.Origin.NETWORK && !Thread.currentThread().isInterrupted()) {
            try { saveCache(cache, result.text); }
            catch (Exception e) { note += "本次目录可用，但缓存保存失败；"; }
        }
        return new Loaded(result.entries, source.name + "：" + result.entries.size() + " 条线路 " + note + "使用" + result.label);
    }

    private void saveCache(File cache, String text) throws Exception {
        File dir = cache.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("缓存目录不可写");
        File tmp = new File(cache.getPath() + "." + java.util.UUID.randomUUID() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(cache)) throw new java.io.IOException("缓存保存失败");
        } finally { if (tmp.exists()) tmp.delete(); }
    }
}
