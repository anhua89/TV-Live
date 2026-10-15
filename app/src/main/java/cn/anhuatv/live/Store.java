package cn.anhuatv.live;

import android.content.Context;
import android.content.SharedPreferences;
import cn.anhuatv.live.core.Models.Channel;
import cn.anhuatv.live.core.Models.Source;
import cn.anhuatv.live.core.Models.Stream;
import cn.anhuatv.live.core.RouteOrder.Health;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Store {
    private final Context context;
    private final SharedPreferences prefs;
    private final Map<String, Health> health = new HashMap<>();
    public Store(Context context) {
        this.context = context.getApplicationContext(); prefs = context.getSharedPreferences("tv1", Context.MODE_PRIVATE);
    }
    public List<Source> sources() {
        String text = prefs.getString("sources", "");
        if (text.isEmpty()) {
            try (InputStream in = context.getAssets().open("sources.json")) { text = Net.decode(Net.read(in, 100000)); }
            catch (Exception e) { throw new IllegalStateException("内置订阅缺失", e); }
        }
        List<Source> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
                out.add(new Source(j.getString("id"), j.getString("name"), j.getString("url"),
                        j.optBoolean("enabled", false), j.optInt("priority", i * 10)));
            }
        } catch (Exception e) { throw new IllegalStateException("订阅配置读取失败", e); }
        return out;
    }
    public void saveSources(List<Source> sources) {
        JSONArray arr = new JSONArray();
        try {
            for (Source s : sources) {
                arr.put(new JSONObject().put("id", s.id).put("name", s.name).put("url", s.url)
                        .put("enabled", s.enabled).put("priority", s.priority));
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
        prefs.edit().putString("sources", arr.toString()).apply();
    }
    public Set<String> favorites() { return new HashSet<>(prefs.getStringSet("favorites", new HashSet<>())); }
    public boolean toggleFavorite(Channel c) {
        Set<String> set = favorites(); boolean added = !set.remove(c.id); if (added) set.add(c.id);
        prefs.edit().putStringSet("favorites", set).apply(); return added;
    }
    public String lastChannel() { return prefs.getString("last_channel", "CCTV1"); }
    public void lastChannel(String id) { prefs.edit().putString("last_channel", id).apply(); }
    public String preferred(Channel c) { return prefs.getString("preferred_" + digest(c.id), ""); }
    public void prefer(Channel c, Stream s) { prefs.edit().putString("preferred_" + digest(c.id), s.key()).apply(); }
    public synchronized void recordNetwork(Stream stream, long ms, boolean failed) {
        Health old = metric(stream);
        long frame = old == null ? -1 : old.frameMs;
        long now = System.currentTimeMillis();
        record(stream, new Health(ms, frame, now, failed, now, old == null ? 0 : old.frameAt));
    }
    public synchronized void recordFrame(Stream s, long ms, boolean failed) {
        Health old = metric(s);
        long now = System.currentTimeMillis();
        record(s, new Health(old == null ? -1 : old.networkMs, ms, now, failed, old == null ? 0 : old.networkAt, now));
    }
    private void record(Stream s, Health h) {
        health.put(s.key(), h);
        prefs.edit().putString("health_" + digest(s.key()),
                h.networkMs + "," + h.frameMs + "," + h.testedAt + "," + h.failed + "," + h.networkAt + "," + h.frameAt).apply();
    }
    public synchronized Health metric(Stream s) {
        Health h = health.get(s.key());
        if (h != null) return freshFields(h);
        String data = prefs.getString("health_" + digest(s.key()), "");
        try {
            String[] p = data.split(",");
            h = new Health(Long.parseLong(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2]), Boolean.parseBoolean(p[3]),
                    Long.parseLong(p[4]), Long.parseLong(p[5]));
            health.put(s.key(), h); return freshFields(h);
        } catch (Exception e) { return null; }
    }
    private Health freshFields(Health h) {
        long now = System.currentTimeMillis();
        long net = now >= h.networkAt && now - h.networkAt < cn.anhuatv.live.core.RouteOrder.VALID_FOR_MS ? h.networkMs : -1;
        long frame = now >= h.frameAt && now - h.frameAt < cn.anhuatv.live.core.RouteOrder.VALID_FOR_MS ? h.frameMs : -1;
        return new Health(net, frame, h.testedAt, h.failed, h.networkAt, h.frameAt);
    }
    public synchronized Map<String, Health> metrics(List<Stream> streams) {
        Map<String, Health> map = new HashMap<>();
        for (Stream s : streams) { Health h = metric(s); if (h != null) map.put(s.key(), h); }
        return map;
    }
    public static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder(); for (byte b : hash) s.append(String.format(Locale.ROOT, "%02x", b & 255));
            return s.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
