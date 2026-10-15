package cn.anhuatv.live.core;

import cn.anhuatv.live.core.Models.Stream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class RouteOrder {
    public static final long VALID_FOR_MS = 24L * 60 * 60 * 1000;
    private RouteOrder() {}

    public static final class Health {
        public final long networkMs, frameMs, testedAt, networkAt, frameAt;
        public final boolean failed;
        public Health(long networkMs, long frameMs, long testedAt, boolean failed) {
            this(networkMs, frameMs, testedAt, failed, testedAt, testedAt);
        }
        public Health(long networkMs, long frameMs, long testedAt, boolean failed, long networkAt, long frameAt) {
            this.networkMs = networkMs; this.frameMs = frameMs; this.testedAt = testedAt; this.failed = failed;
            this.networkAt = networkAt; this.frameAt = frameAt;
        }
    }
    public static List<Stream> sorted(List<Stream> streams, Map<String, Health> metrics,
                                      String preferredKey, long now) {
        List<Stream> out = new ArrayList<>(streams);
        Collections.sort(out, (a, b) -> {
            Health ah = fresh(metrics.get(a.key()), now), bh = fresh(metrics.get(b.key()), now);
            int bucketA = bucket(a, ah, preferredKey), bucketB = bucket(b, bh, preferredKey);
            if (bucketA != bucketB) return Integer.compare(bucketA, bucketB);
            // Source priority still matters; response time never proves video decodability.
            int p = Integer.compare(a.priority, b.priority);
            if (p != 0) return p;
            long at = ah == null ? Long.MAX_VALUE : ah.frameMs >= 0 ? ah.frameMs : ah.networkMs;
            long bt = bh == null ? Long.MAX_VALUE : bh.frameMs >= 0 ? bh.frameMs : bh.networkMs;
            return Long.compare(at, bt);
        });
        return out;
    }
    private static Health fresh(Health h, long now) {
        if (h == null || now < h.testedAt || now - h.testedAt >= VALID_FOR_MS) return null;
        long network = now >= h.networkAt && now - h.networkAt < VALID_FOR_MS ? h.networkMs : -1;
        long frame = now >= h.frameAt && now - h.frameAt < VALID_FOR_MS ? h.frameMs : -1;
        return new Health(network, frame, h.testedAt, h.failed, h.networkAt, h.frameAt);
    }
    private static int bucket(Stream s, Health h, String preferred) {
        if (h != null && h.failed) return 4;
        if (s.key().equals(preferred)) return 0;
        if (h != null && h.frameMs >= 0) return 1;
        return h != null && h.networkMs >= 0 ? 2 : 3;
    }
}
