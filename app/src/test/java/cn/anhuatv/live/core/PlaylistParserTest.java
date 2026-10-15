package cn.anhuatv.live.core;

import org.junit.Test;
import static org.junit.Assert.*;
import cn.anhuatv.live.core.Models.*;
import java.util.List;

public class PlaylistParserTest {
    private final Source source = new Source("test", "test", "https://example.com/list", true, 0);
    @Test public void parsesIpv6AndCatchupWithoutManglingBrackets() {
        List<Entry> e = PlaylistParser.parse("\uFEFF#EXTM3U\n#EXTINF:-1 tvg-id=\"c1\" group-title=\"成都\" catchup=\"append\" catchup-source=\"?start={utc}\",CDTV-1\nhttp://[2409:8c02::2b]/x.m3u8?token=a,b\n", source);
        assertEquals(1, e.size()); assertEquals("http://[2409:8c02::2b]/x.m3u8?token=a,b", e.get(0).stream.url);
        assertEquals("append", e.get(0).stream.catchup); assertEquals("?start={utc}", e.get(0).stream.catchupSource);
    }
    @Test public void commaInsideQuotedAttributeDoesNotSplitTitle() {
        Entry e = PlaylistParser.parse("#EXTINF:-1 group-title=\"local,china\" tvg-name=\"foo\",CCTV-1\nhttps://example.com/1.m3u8", source).get(0);
        assertEquals("CCTV-1", e.name); assertEquals("local,china", e.stream.group);
    }
    @Test public void txtGenreAndRoutesSupported() {
        List<Entry> e = PlaylistParser.parse("央视,#genre#\nCCTV1,http://example.com/a\nCCTV2,https://example.com/b\n", source);
        assertEquals(2, e.size()); assertEquals("央视", e.get(1).stream.group);
    }
    @Test public void vlcHeadersAndPipeHeadersAreMergedAndDoNotLeak() {
        List<Entry> e = PlaylistParser.parse("#EXTINF:-1,CCTV1\n#EXTVLCOPT:http-user-agent=MyTV\n#EXTVLCOPT:http-referrer=https://example.com/\nhttps://example.com/a|Origin=https%3A%2F%2Fexample.com\n#EXTINF:-1,CCTV2\nhttps://example.com/b", source);
        assertEquals("MyTV", e.get(0).stream.headers.get("User-Agent"));
        assertEquals("https://example.com", e.get(0).stream.headers.get("Origin"));
        assertTrue(e.get(1).stream.headers.isEmpty());
    }
    @Test public void rejectsWebAndMulticastSchemes() {
        List<Entry> e = PlaylistParser.parse("bad,javascript:alert(1)\nx,udp://239.1.1.1:1234\ny,file:///private\n", source);
        assertTrue(e.isEmpty());
    }
    @Test public void supportsFlatExtHttp() {
        Entry e = PlaylistParser.parse("#EXTINF:-1,A\n#EXTHTTP:{\"User-Agent\":\"TV\",\"Referer\":\"https://site/\"}\nhttps://example.com/a", source).get(0);
        assertEquals("TV", e.stream.headers.get("User-Agent"));
    }
    @Test public void rtspUnicastParsed() {
        assertEquals(1, PlaylistParser.parse("Camera,rtsp://example.com/live", source).size());
    }
    @Test public void headerInjectionIsIgnored() {
        Entry e = PlaylistParser.parse("A,https://example.com/a|User-Agent=Good%0D%0ABad%3Ayes", source).get(0);
        assertTrue(e.stream.headers.isEmpty());
    }
}
