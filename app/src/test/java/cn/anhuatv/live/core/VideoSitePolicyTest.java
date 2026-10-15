package cn.anhuatv.live.core;

import org.junit.Test;
import static org.junit.Assert.*;
import cn.anhuatv.live.core.WebChannelRoutes.Site;

public class VideoSitePolicyTest {
    @Test public void miguUsesWiderDesktopWithoutChangingOtherSites() {
        assertEquals(1920, VideoSitePolicy.desktopWidth(Site.MIGU));
        assertEquals(1280, VideoSitePolicy.desktopWidth(Site.YOUKU));
        assertEquals(1280, VideoSitePolicy.desktopWidth(Site.CHENGDU));
        assertFalse(VideoSitePolicy.enabled(Site.CHENGDU));
    }
    @Test public void youkuKeepsSameEpisodeWhenChangingWebVersion() {
        String desktop = "https://v.youku.com/v_show/id_XNjUzMDk4NjM5Mg==.html?from=home%2Fweb#player";
        String mobile = "https://m.youku.com/video/id_XNjUzMDk4NjM5Mg==.html?from=home%2Fweb#player";
        assertEquals(mobile, VideoSitePolicy.page(Site.YOUKU, desktop, false));
        assertEquals(desktop, VideoSitePolicy.page(Site.YOUKU, mobile, true));
        assertEquals(mobile, VideoSitePolicy.page(Site.YOUKU, mobile, false));
    }
    @Test public void miguKeepsOfficialDetailVerticalAndLiveIdentifiers() {
        for (String path : new String[]{"detail/953150738", "vertical/966790046", "live/abc_123"}) {
            String desktop = "https://www.miguvideo.com/p/" + path;
            String mobile = "https://m.miguvideo.com/m/" + path.replace("live/", "live/home/");
            assertEquals(mobile, VideoSitePolicy.page(Site.MIGU, desktop, false));
            assertEquals(desktop, VideoSitePolicy.page(Site.MIGU, mobile, true));
        }
    }
    @Test public void unrecognizedLoginAndCustomPagesAreNeverRewritten() {
        for (String url : new String[]{"https://passport.youku.com/login", "https://v.youku.com/v_show/id_bad/other.html", "https://www.youku.com:8443/", "https://www.youku.com.example.org/", "https://example.com/play", "javascript:alert(1)"})
            assertEquals(url, VideoSitePolicy.page(Site.YOUKU, url, false));
        assertEquals(WebChannelRoutes.DEFAULT_URL, VideoSitePolicy.page(Site.CHENGDU, WebChannelRoutes.DEFAULT_URL, false));
        assertNull(VideoSitePolicy.page(Site.MIGU, null, false));
    }
    @Test public void homeCanSwitchWithoutInventingMediaUrls() {
        assertEquals("https://m.miguvideo.com/m/home/", VideoSitePolicy.page(Site.MIGU, Site.MIGU.homeUrl, false));
        assertEquals("https://m.youku.com/", VideoSitePolicy.page(Site.YOUKU, Site.YOUKU.homeUrl, false));
        assertEquals(Site.YOUKU.homeUrl, VideoSitePolicy.page(Site.YOUKU, "https://m.youku.com/", true));
    }
    @Test public void mobileAgentUsesRealEngineVersion() {
        String original = "Mozilla/5.0 (Linux; Android 9; TCL Build/TV; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/71.0.3578.99 Safari/537.36";
        String mobile = VideoSitePolicy.userAgent(original, false);
        assertTrue(mobile.contains("Android 9")); assertTrue(mobile.contains("Chrome/71.0.3578.99"));
        assertTrue(mobile.contains("Mobile Safari/")); assertFalse(mobile.contains("Version/4.0")); assertFalse(mobile.contains("; wv"));
        assertEquals(mobile, VideoSitePolicy.userAgent(mobile, false));
        assertEquals(WebPagePolicy.desktopUserAgent(original), VideoSitePolicy.userAgent(original, true));
    }
    @Test public void playerTimeoutOnlyAppliesToPlaybackPages() {
        assertTrue(VideoSitePolicy.isPlaybackPage(Site.YOUKU, "https://v.youku.com/v_show/id_X123==.html"));
        assertTrue(VideoSitePolicy.isPlaybackPage(Site.MIGU, "https://m.miguvideo.com/m/live/home/123"));
        assertFalse(VideoSitePolicy.isPlaybackPage(Site.YOUKU, Site.YOUKU.homeUrl));
        assertFalse(VideoSitePolicy.isPlaybackPage(Site.MIGU, "https://example.com/p/detail/123"));
    }
    @Test public void protectedMediaRequiresCurrentOfficialHttpsOrigin() {
        assertTrue(VideoSitePolicy.protectedMediaOrigin(Site.YOUKU, "https://v.youku.com/v_show/id_X1.html", "https://v.youku.com/"));
        for (String origin : new String[]{"https://other.youku.com/", "https://v.youku.com.example.org/", "http://v.youku.com/", "https://v.youku.com:8443/", "https://user:password@v.youku.com/"})
            assertFalse(origin, VideoSitePolicy.protectedMediaOrigin(Site.YOUKU, "https://v.youku.com/v_show/id_X1.html", origin));
        assertFalse(VideoSitePolicy.protectedMediaOrigin(Site.CHENGDU, WebChannelRoutes.DEFAULT_URL, "https://cditv.cn/"));
    }
}
