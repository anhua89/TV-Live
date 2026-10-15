package cn.anhuatv.live.core;

import org.junit.Test;
import static org.junit.Assert.*;
import cn.anhuatv.live.core.Models.*;
import java.util.*;

public class CatalogTest {
    private Entry entry(String name, String url, int priority) {
        return new Entry(name, "shared", new Stream(url, new Source("s" + priority, "s", "", true, priority), "", Collections.emptyMap(), "", ""));
    }
    @Test public void preservesSportsPlus() {
        assertEquals("CCTV5+", Catalog.normalize("CCTV-5+ 高清"));
        assertNotEquals(Catalog.normalize("CCTV5+"), Catalog.normalize("CCTV5体育"));
    }
    @Test public void preservesUhdAndInternationalEditions() {
        assertNotEquals(Catalog.normalize("CCTV4K超高清"), Catalog.normalize("CCTV4"));
        assertNotEquals(Catalog.normalize("CCTV8K"), Catalog.normalize("CCTV8"));
        assertNotEquals(Catalog.normalize("CCTV4欧洲"), Catalog.normalize("CCTV4美洲"));
    }
    @Test public void mergesDuplicatesAndHonorsPriority() {
        List<Channel> channels = Catalog.merge(Arrays.asList(entry("CCTV-1高清", "https://a/a", 20), entry("CCTV1", "https://b/b", 0), entry("CCTV1", "https://a/a", 30)));
        assertEquals(1, channels.size()); assertEquals(2, channels.get(0).streams.size());
        assertEquals("https://b/b", channels.get(0).streams.get(0).url);
    }
    @Test public void sharedTvgIdDoesNotMergeDifferentNames() {
        assertEquals(2, Catalog.merge(Arrays.asList(entry("四川卫视", "https://a", 0), entry("成都新闻", "https://b", 0))).size());
    }
    @Test public void naturallySortsNumberedStations() {
        List<Channel> c = Catalog.merge(Arrays.asList(entry("CCTV10", "https://a", 0), entry("CCTV5+", "https://b", 0), entry("CCTV5", "https://c", 0), entry("CCTV6", "https://d", 0)));
        assertEquals("CCTV5", c.get(0).id); assertEquals("CCTV5+", c.get(1).id);
        assertEquals("CCTV6", c.get(2).id); assertEquals("CCTV10", c.get(3).id);
    }
    @Test public void classifiesChengdu() {
        Channel c = Catalog.merge(Collections.singletonList(entry("CDTV-1", "https://a", 0))).get(0);
        assertEquals("成都", c.group);
    }
    @Test public void distinctHeadersKeepRoutesSeparate() {
        Source s = new Source("s", "s", "", true, 0);
        Entry a = new Entry("A", "", new Stream("https://a", s, "", Collections.singletonMap("Referer", "https://r1"), "", ""));
        Entry b = new Entry("A", "", new Stream("https://a", s, "", Collections.singletonMap("Referer", "https://r2"), "", ""));
        assertEquals(2, Catalog.merge(Arrays.asList(a, b)).get(0).streams.size());
    }
}
