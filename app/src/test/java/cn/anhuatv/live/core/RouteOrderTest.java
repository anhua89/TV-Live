package cn.anhuatv.live.core;

import org.junit.Test;
import static org.junit.Assert.*;
import cn.anhuatv.live.core.Models.*;
import cn.anhuatv.live.core.RouteOrder.Health;
import java.util.*;

public class RouteOrderTest {
    private final long now = 100000000;
    private Stream stream(String id, int priority) {
        return new Stream("https://" + id, new Source(id, id, "", true, priority), "", Collections.emptyMap(), "", "");
    }
    @Test public void previousSuccessIsPreferred() {
        Stream a = stream("a", 0), b = stream("b", 10);
        assertSame(b, RouteOrder.sorted(Arrays.asList(a, b), Collections.emptyMap(), b.key(), now).get(0));
    }
    @Test public void recentFailureOverridesPreference() {
        Stream a = stream("a", 0), b = stream("b", 10);
        Map<String, Health> h = Collections.singletonMap(a.key(), new Health(-1, -1, now, true));
        assertSame(b, RouteOrder.sorted(Arrays.asList(a, b), h, a.key(), now).get(0));
    }
    @Test public void renderedRouteBeatsMerelyResponsiveOne() {
        Stream a = stream("a", 0), b = stream("b", 10); Map<String, Health> h = new HashMap<>();
        h.put(a.key(), new Health(1, -1, now, false)); h.put(b.key(), new Health(1000, 2000, now, false));
        assertSame(b, RouteOrder.sorted(Arrays.asList(a, b), h, "", now).get(0));
    }
    @Test public void expiredFailureDoesNotPoisonSource() {
        Stream a = stream("a", 0), b = stream("b", 10);
        Map<String, Health> h = Collections.singletonMap(a.key(), new Health(-1, -1, now - RouteOrder.VALID_FOR_MS - 1, true));
        assertSame(a, RouteOrder.sorted(Arrays.asList(b, a), h, "", now).get(0));
    }
    @Test public void sourceListIsNotMutatedBySort() {
        Stream a = stream("a", 20), b = stream("b", 0); List<Stream> original = Arrays.asList(a, b);
        RouteOrder.sorted(original, Collections.emptyMap(), "", now); assertSame(a, original.get(0));
    }
    @Test public void networkRetestDoesNotReviveExpiredFrameEvidence() {
        Stream a = stream("a", 0), b = stream("b", 10); Map<String, Health> h = new HashMap<>();
        h.put(a.key(), new Health(5, 10, now, false, now, now - RouteOrder.VALID_FOR_MS - 1));
        h.put(b.key(), new Health(100, 2000, now, false));
        assertSame(b, RouteOrder.sorted(Arrays.asList(a, b), h, "", now).get(0));
    }
}
