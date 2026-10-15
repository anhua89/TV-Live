package cn.anhuatv.live;

import cn.anhuatv.live.core.Models.Stream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class Net {
    private Net() {}
    public static final String USER_AGENT = "AnhuaTV/1.0 (Android TV; Media3)";
    public static final class Response implements AutoCloseable {
        public final HttpURLConnection connection;
        public final InputStream body;
        Response(HttpURLConnection c, InputStream b) { connection = c; body = b; }
        public void close() throws IOException { try { body.close(); } finally { connection.disconnect(); } }
    }

    public static Response open(String address, Map<String, String> headers, int timeout,
                                boolean requireHttps) throws IOException {
        URL url;
        try { url = new URL(new URI(address).toASCIIString()); }
        catch (Exception e) { throw new IOException("地址格式不正确", e); }
        for (int redirects = 0; redirects <= 6; redirects++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
            String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
            if ((!protocol.equals("https") && !protocol.equals("http")) ||
                    (requireHttps && !protocol.equals("https"))) throw new IOException("不支持的协议");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(timeout); c.setReadTimeout(timeout);
            c.setRequestProperty("User-Agent", USER_AGENT); c.setRequestProperty("Accept-Encoding", "gzip");
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            int status;
            try { status = c.getResponseCode(); } catch (IOException e) { c.disconnect(); throw e; }
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = c.getHeaderField("Location"); c.disconnect();
                if (location == null) throw new IOException("重定向缺少地址");
                URL next = new URL(url, location);
                if (requireHttps && url.getProtocol().equals("https") && next.getProtocol().equals("http")) {
                    throw new IOException("拒绝从 HTTPS 降级到 HTTP");
                }
                if (!url.getHost().equalsIgnoreCase(next.getHost())) {
                    Map<String, String> filtered = new java.util.LinkedHashMap<>(headers);
                    filtered.remove("Authorization"); filtered.remove("Cookie"); headers = filtered;
                }
                url = next; continue;
            }
            if (status < 200 || status >= 300) { c.disconnect(); throw new IOException("HTTP " + status); }
            try {
                InputStream in = c.getInputStream();
                if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
                return new Response(c, in);
            } catch (IOException e) { c.disconnect(); throw e; }
        }
        throw new IOException("重定向次数过多");
    }

    public static String text(String url, Map<String, String> headers, int cap, boolean https) throws IOException {
        try (Response r = open(url, headers, 7000, https)) { return decode(read(r.body, cap)); }
    }
    public static byte[] read(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192]; int n;
        while ((n = in.read(chunk)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
            if (b.size() + n > cap) throw new IOException("内容超过大小限制");
            b.write(chunk, 0, n);
        }
        return b.toByteArray();
    }
    public static String decode(byte[] b) {
        String utf = new String(b, Charset.forName("UTF-8"));
        return utf.indexOf('\uFFFD') >= 0 ? new String(b, Charset.forName("GB18030")) : utf;
    }

    public static final class Probe {
        public final long elapsedMs;
        public final int bytes;
        public final String detail;
        Probe(long ms, int bytes, String detail) { elapsedMs = ms; this.bytes = bytes; this.detail = detail; }
    }
    public static final class UnsupportedProbeException extends IOException {
        UnsupportedProbeException(String message) { super(message); }
    }

    /** Bounded first media sample GET, not HEAD, never presented as a decoder test. */
    public static Probe probe(Stream stream) throws IOException {
        if (stream.url.startsWith("rtsp:")) throw new UnsupportedProbeException("RTSP 请使用逐路出画实测");
        long begin = System.nanoTime();
        String url = stream.url;
        for (int depth = 0; depth < 5; depth++) {
            if ((System.nanoTime() - begin) / 1_000_000 > 18000) throw new IOException("探测总时长超限");
            try (Response r = open(url, stream.headers, 3500, false)) {
                String actualUrl = r.connection.getURL().toString();
                String contentType = r.connection.getContentType();
                ByteArrayOutputStream sample = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while (sample.size() < 65536 && (n = r.body.read(chunk, 0, Math.min(chunk.length, 65536 - sample.size()))) > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
                    sample.write(chunk, 0, n);
                    if ((System.nanoTime() - begin) / 1_000_000 > 18000) break;
                }
                byte[] data = sample.toByteArray();
                String body = new String(data, Charset.forName("UTF-8")).replace("\uFEFF", "").trim();
                if (body.startsWith("#EXTM3U")) {
                    String next = null;
                    for (String line : body.split("\\r?\\n")) {
                        String v = line.trim();
                        if (!v.isEmpty() && !v.startsWith("#")) { next = v; break; }
                    }
                    if (next == null) throw new IOException("HLS 暂无分片；请实播复核");
                    try { url = new URI(actualUrl).resolve(next).toASCIIString(); }
                    catch (Exception e) { throw new IOException("HLS 地址解析失败", e); }
                    continue;
                }
                String lower = body.toLowerCase(Locale.ROOT);
                if (data.length < 188 || lower.startsWith("<!doctype") || lower.startsWith("<html") ||
                        (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html"))) {
                    throw new IOException("返回内容不像媒体数据");
                }
                if (lower.startsWith("<?xml") || lower.startsWith("<mpd")) {
                    throw new UnsupportedProbeException("DASH 清单请使用逐路出画实测");
                }
                return new Probe((System.nanoTime() - begin) / 1_000_000, data.length,
                        "媒体抽样 " + (data.length / 1024) + " KB；未验证解码");
            }
        }
        throw new IOException("HLS 清单嵌套过深");
    }
    public static Map<String, String> noHeaders() { return Collections.emptyMap(); }
}
