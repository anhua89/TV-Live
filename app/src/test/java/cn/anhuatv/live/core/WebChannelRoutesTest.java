package cn.anhuatv.live.core;

import cn.anhuatv.live.core.Models.Channel;
import cn.anhuatv.live.core.Models.Source;
import cn.anhuatv.live.core.Models.Stream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebChannelRoutesTest {
    private Channel channel(String name, String group) { return new Channel(Catalog.normalize(name), name, group, Collections.emptyList()); }

    @Test public void chengduChannelAliasesUseWeb() {
        for (String name : Arrays.asList("CDTV-1高清", "CDTV2经济资讯", "CDTV6", "成都卫视", "成都新闻综合频道", "成都电视台影视文艺频道", "成都-1", "成都二套"))
            assertTrue(name, WebChannelRoutes.usesWeb(channel(name, "成都")));
    }
    @Test public void nativeStationsKeepOriginalRoutingEvenInMislabelledGroup() {
        for (String name : Arrays.asList("四川卫视", "CCTV-1", "CCTV-5+", "四川新闻", "重庆卫视", "成都地铁", "CDTV11", "CDTV0"))
            assertFalse(name, WebChannelRoutes.usesWeb(channel(name, "成都")));
        assertFalse(WebChannelRoutes.usesWeb(null));
    }
    @Test public void webEntryExistsWithoutAnySubscription() {
        List<Channel> result = WebChannelRoutes.withWebEntry(Collections.emptyList());
        assertEquals(3, result.size()); assertTrue(WebChannelRoutes.usesWeb(result.get(0)));
        assertEquals("成都", result.get(0).group); assertTrue(result.get(0).streams.isEmpty());
        assertEquals(WebChannelRoutes.MIGU_ID, result.get(1).id);
        assertEquals(WebChannelRoutes.YOUKU_ID, result.get(2).id);
        for (Channel c : result) { assertTrue(WebChannelRoutes.usesWeb(c)); assertTrue(c.streams.isEmpty()); }
    }
    @Test public void existingChengduChannelsAndFavoritesIdsArePreserved() {
        Channel original = channel("CDTV1", "成都");
        List<Channel> result = WebChannelRoutes.withWebEntry(Arrays.asList(channel("四川卫视", "卫视"), original));
        assertEquals(4, result.size()); assertSame(original, result.get(1)); assertEquals("CDTV1", result.get(1).id);
    }
    @Test public void addingWebEntryPreservesNativeStreamsAndOrder() {
        Source source = new Source("local", "Local", "local://test", true, 0);
        Stream stream = new Stream("https://example.com/live.m3u8", source, "卫视", Collections.emptyMap(), "", "");
        Channel cctv = channel("CCTV1", "央视"), satellite = new Channel("四川卫视", "四川卫视", "卫视", Arrays.asList(stream));
        Channel other = channel("其他台", "其他");
        List<Channel> input = Arrays.asList(cctv, satellite, other);
        List<Channel> result = WebChannelRoutes.withWebEntry(input);
        assertEquals(3, input.size()); assertEquals(6, result.size());
        assertSame(cctv, result.get(0)); assertSame(satellite, result.get(1)); assertSame(other, result.get(3));
        assertSame(stream, result.get(1).streams.get(0));
        assertEquals(WebChannelRoutes.MIGU_ID, result.get(4).id);
        assertEquals(WebChannelRoutes.YOUKU_ID, result.get(5).id);
        assertEquals(6, WebChannelRoutes.withWebEntry(result).size());
    }
    @Test public void platformEntriesRouteSeparatelyWithoutHijackingNativeNames() {
        assertEquals(WebChannelRoutes.Site.MIGU, WebChannelRoutes.site(new Channel(WebChannelRoutes.MIGU_ID, "咪咕视频（网页）", "其他", Collections.emptyList())));
        assertEquals(WebChannelRoutes.Site.YOUKU, WebChannelRoutes.site(new Channel(WebChannelRoutes.YOUKU_ID, "优酷视频（网页）", "其他", Collections.emptyList())));
        assertEquals(WebChannelRoutes.Site.CHENGDU, WebChannelRoutes.site(channel("成都卫视", "成都")));
        for (String name : Arrays.asList("咪咕体育", "优酷视频", "优酷电影", "咪咕视频（网页）"))
            assertFalse(name, WebChannelRoutes.usesWeb(channel(name, "其他")));
    }
    @Test public void rebuildingCatalogNeverDuplicatesPlatformEntries() {
        List<Channel> first = WebChannelRoutes.withWebEntry(Collections.singletonList(channel("CDTV1", "成都")));
        List<Channel> second = WebChannelRoutes.withWebEntry(first);
        assertEquals(3, second.size());
        for (int i = 0; i < first.size(); i++) assertSame(first.get(i), second.get(i));
    }
    @Test public void siteKeysAndPreferencesRetainChengduAndSeparateNewSites() {
        assertEquals(WebChannelRoutes.Site.CHENGDU, WebChannelRoutes.Site.fromKey(null));
        assertEquals(WebChannelRoutes.Site.CHENGDU, WebChannelRoutes.Site.fromKey("https://example.com/"));
        assertEquals("page_url", WebChannelRoutes.Site.CHENGDU.urlPreference());
        assertEquals("desktop_mode", WebChannelRoutes.Site.CHENGDU.desktopPreference());
        java.util.Set<String> urls = new java.util.HashSet<>(), modes = new java.util.HashSet<>();
        for (WebChannelRoutes.Site site : WebChannelRoutes.Site.values()) {
            assertSame(site, WebChannelRoutes.Site.fromKey(site.key));
            assertTrue(urls.add(site.urlPreference())); assertTrue(modes.add(site.desktopPreference()));
        }
    }
    @Test public void publicHttpPagesAllowQueryFragmentsAndIpv6() {
        for (String value : Arrays.asList(WebChannelRoutes.DEFAULT_URL, "http://example.com/live?ch=1#play", "https://[2409:1234::1]/tv"))
            assertTrue(value, WebChannelRoutes.validPageUrl(value));
    }
    @Test public void unsafeOrMalformedPageUrlsAreRejected() {
        for (String value : Arrays.asList("javascript:alert(1)", "file:///etc/passwd", "content://files/a", "intent://player", "https://user:pass@example.com/", "https://", "https://example.com:99999/", "https://example.com/ a", " https://example.com/", "https://example.com/\n"))
            assertFalse(value, WebChannelRoutes.validPageUrl(value));
        assertFalse(WebChannelRoutes.validPageUrl(null));
    }
}
