package cn.anhuatv.live.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class WebPagePolicyTest {
    @Test public void homepageMatchesScreenshotInsteadOfHistoricalArticlePath() {
        assertEquals("https://cditv.cn/", WebChannelRoutes.DEFAULT_URL);
        assertTrue(WebPagePolicy.isHome(WebChannelRoutes.DEFAULT_URL));
        assertTrue(WebPagePolicy.isHome("http://www.cditv.cn/index.html"));
        assertFalse(WebPagePolicy.isHome("https://www.cditv.cn/html/cdrtv/"));
        assertFalse(WebPagePolicy.isHome("https://twlive.cditv.cn/"));
    }
    @Test public void oldSavedDefaultMigratesWithoutKeepingWrongNewsEntry() {
        assertEquals(WebChannelRoutes.DEFAULT_URL, WebPagePolicy.startUrl("https://www.cditv.cn/html/cdrtv/#live"));
        assertEquals(WebChannelRoutes.DEFAULT_URL, WebPagePolicy.startUrl("http://cditv.cn/html/cdrtv"));
    }
    @Test public void personalPageChoicesArePreserved() {
        String custom = "https://example.com/my-live?channel=2#play";
        assertEquals(custom, WebPagePolicy.startUrl(custom));
        String news = "https://cditv.cn/show/123.html";
        assertEquals(news, WebPagePolicy.startUrl(news));
        String query = "https://www.cditv.cn/html/cdrtv/?custom=1";
        assertEquals(query, WebPagePolicy.startUrl(query));
    }
    @Test public void invalidSavedPageFallsBackToHomepage() {
        for (String value : new String[]{null,"", "javascript:alert(1)", "https://user:pw@cditv.cn/"})
            assertEquals(WebChannelRoutes.DEFAULT_URL, WebPagePolicy.startUrl(value));
    }
    @Test public void officialCompatibilityDoesNotExtendToLookalikeDomains() {
        assertTrue(WebPagePolicy.isCditv("https://www.cditv.cn/"));
        assertTrue(WebPagePolicy.isCditv("https://twlive.cditv.cn/play"));
        for (String value : new String[]{"https://cditv.cn.evil.com/", "https://fakecditv.cn/", "file:///cditv.cn/a", "https://cditv.cn@evil.com/"})
            assertFalse(value, WebPagePolicy.isCditv(value));
    }
    @Test public void desktopModePreservesRealChromiumVersion() {
        String phone = "Mozilla/5.0 (Linux; Android 9; TCL Build/TV; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/71.0.3578.99 Mobile Safari/537.36";
        String desktop = WebPagePolicy.desktopUserAgent(phone);
        assertTrue(desktop.contains("X11; Linux x86_64")); assertTrue(desktop.contains("Chrome/71.0.3578.99"));
        assertFalse(desktop.contains("Android")); assertFalse(desktop.contains("Mobile")); assertFalse(desktop.contains("Version/4.0"));
    }
    @Test public void desktopUaIsIdempotentAndHandlesMissingAgent() {
        String original = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36";
        assertEquals(original, WebPagePolicy.desktopUserAgent(original));
        assertEquals("", WebPagePolicy.desktopUserAgent(null));
    }
    @Test public void diagnosticsRemoveQuerySecretsAndFragments() {
        assertEquals("https://cditv.cn/live", WebPagePolicy.displayUrl("https://cditv.cn/live?token=secret&key=hidden#session"));
        assertEquals("", WebPagePolicy.displayUrl("file:///private/key"));
    }
}
