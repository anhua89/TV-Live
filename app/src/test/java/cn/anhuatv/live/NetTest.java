package cn.anhuatv.live;

import cn.anhuatv.live.core.Models.Source;
import cn.anhuatv.live.core.Models.Stream;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Standard URL handler fixture: deterministic HTTP behavior, no external network required. */
public class NetTest {
    private static final Map<String, Reply> replies = new HashMap<>();
    private static final Map<String, Fake> requests = new HashMap<>();
    static {
        URL.setURLStreamHandlerFactory(protocol -> {
            if (!protocol.equals("http") && !protocol.equals("https")) return null;
            return new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL u) throws IOException {
                    Reply r = replies.get(u.toString());
                    if (r == null) throw new IOException("Unexpected request: " + u);
                    Fake f = new Fake(u, r); requests.put(u.toString(), f); return f;
                }
            };
        });
    }
    private static final class Reply {
        final byte[] bytes; int code = 200; String location, encoding;
        Reply(String s) { this(s.getBytes(StandardCharsets.UTF_8)); }
        Reply(byte[] b) { bytes = b; }
    }
    private static final class Fake extends HttpURLConnection {
        final Reply r; boolean disconnected;
        Fake(URL u, Reply r) { super(u); this.r = r; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
        @Override public int getResponseCode() { return r.code; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(r.bytes); }
        @Override public String getHeaderField(String name) { return name.equalsIgnoreCase("Location") ? r.location : null; }
        @Override public String getContentEncoding() { return r.encoding; }
        @Override public String getContentType() { return "application/octet-stream"; }
    }
    @Before public void reset() { replies.clear(); requests.clear(); }
    private Stream stream(String url) { return new Stream(url, new Source("s", "s", "", true, 0), "", Collections.emptyMap(), "", ""); }
    @Test public void followsRelativeHlsMasterVariantAndSegment() throws Exception {
        replies.put("https://tv.test/root/master.m3u8", new Reply("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nlow/live.m3u8\n"));
        replies.put("https://tv.test/root/low/live.m3u8", new Reply("#EXTM3U\n#EXTINF:6,\n../chunk.ts?token=abc\n"));
        replies.put("https://tv.test/root/chunk.ts?token=abc", new Reply(new byte[188 * 10]));
        assertEquals(1880, Net.probe(stream("https://tv.test/root/master.m3u8")).bytes);
        assertEquals(3, requests.size());
    }
    @Test public void a200HtmlPageIsNotMediaSuccess() throws Exception {
        replies.put("https://tv.test/a", new Reply("<html>" + new String(new char[300]).replace('\0', 'x') + "</html>"));
        try { Net.probe(stream("https://tv.test/a")); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("不像媒体")); }
    }
    @Test public void rejectsHttpsDowngrade() throws Exception {
        Reply redirect = new Reply(""); redirect.code = 302; redirect.location = "http://tv.test/file";
        replies.put("https://tv.test/file", redirect);
        try { Net.open("https://tv.test/file", Net.noHeaders(), 100, true); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("降级")); }
        assertEquals(1, requests.size());
    }
    @Test public void removesCredentialsOnCrossHostRedirect() throws Exception {
        Reply redirect = new Reply(""); redirect.code = 302; redirect.location = "https://cdn.test/file";
        replies.put("https://tv.test/file", redirect); replies.put("https://cdn.test/file", new Reply("ok"));
        Map<String, String> headers = new HashMap<>(); headers.put("Authorization", "secret"); headers.put("Cookie", "session=secret");
        try (Net.Response ignored = Net.open("https://tv.test/file", headers, 100, true)) {
            assertNull(requests.get("https://cdn.test/file").getRequestProperty("Authorization"));
            assertNull(requests.get("https://cdn.test/file").getRequestProperty("Cookie"));
        }
    }
    @Test public void cappedReadRejectsOversizedResponse() throws Exception {
        try { Net.read(new ByteArrayInputStream(new byte[200]), 100); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("大小限制")); }
    }
    @Test public void decompressesHttpGzip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) { out.write("hello".getBytes(StandardCharsets.UTF_8)); }
        Reply reply = new Reply(bytes.toByteArray()); reply.encoding = "gzip";
        replies.put("https://tv.test/list", reply);
        assertEquals("hello", Net.text("https://tv.test/list", Net.noHeaders(), 100, true));
        assertTrue(requests.get("https://tv.test/list").disconnected);
    }
    @Test public void updateFetchRejectsPlainHttpBeforeConnecting() throws Exception {
        try { Net.open("http://tv.test/a", Net.noHeaders(), 100, true); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("协议")); }
        assertTrue(requests.isEmpty());
    }
    @Test public void ordinaryLiveRequestCanFollowProviderHttpRedirect() throws Exception {
        Reply redirect = new Reply(""); redirect.code = 302; redirect.location = "http://cdn.test/live";
        replies.put("https://tv.test/live", redirect); replies.put("http://cdn.test/live", new Reply("media"));
        assertEquals("media", Net.text("https://tv.test/live", Net.noHeaders(), 100, false));
    }
}
