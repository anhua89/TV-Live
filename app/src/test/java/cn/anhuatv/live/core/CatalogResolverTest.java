package cn.anhuatv.live.core;

import cn.anhuatv.live.core.Models.Source;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

public class CatalogResolverTest {
    private final Source source = new Source("best-fan", "best-fan", CatalogResolver.BEST_FAN_URL, true, 0);
    private String list(String suffix) { return "#EXTM3U\n#EXTINF:-1,CCTV1\nhttps://example.com/" + suffix + ".m3u8\n"; }
    private CatalogResolver.Attempt attempt(String name, String text) { return new CatalogResolver.Attempt(name, () -> text); }
    private CatalogResolver.Attempt blocked() { return new CatalogResolver.Attempt("origin", () -> { throw new IOException("connect timeout after 7000ms"); }); }
    @Test public void coldStartLoadsRealBundledCatalogWithoutNetwork() throws Exception {
        String bundled = new String(Files.readAllBytes(new File("src/main/assets/best_fan_seed.m3u").toPath()), StandardCharsets.UTF_8);
        CatalogResolver.Attempt mustNotConnect = new CatalogResolver.Attempt("network", () -> { throw new AssertionError("cold start must not access the network"); });
        CatalogResolver.Result result = CatalogResolver.resolve(source, true, Collections.singletonList(mustNotConnect), null, attempt("seed", bundled));
        assertEquals(CatalogResolver.Origin.BUNDLED, result.origin);
        assertTrue(result.entries.size() >= 20); assertFalse(Catalog.merge(result.entries).isEmpty());
    }
    @Test public void rawTimeoutFallsBackToSeed() {
        CatalogResolver.Result result = CatalogResolver.resolve(source, false, Collections.singletonList(blocked()), null, attempt("seed", list("seed")));
        assertEquals(CatalogResolver.Origin.BUNDLED, result.origin); assertEquals(1, result.entries.size());
        assertTrue(result.warning.contains("网络刷新"));
    }
    @Test public void cacheTakesPrecedenceOverBundledContent() {
        CatalogResolver.Result r = CatalogResolver.resolve(source, true, Collections.emptyList(), attempt("cache", list("cached")), attempt("seed", list("seed")));
        assertEquals(CatalogResolver.Origin.CACHE, r.origin); assertTrue(r.text.contains("cached"));
    }
    @Test public void validNetworkRefreshWins() {
        CatalogResolver.Result r = CatalogResolver.resolve(source, false, Collections.singletonList(attempt("remote", list("fresh"))), attempt("cache", list("old")), null);
        assertEquals(CatalogResolver.Origin.NETWORK, r.origin); assertTrue(r.text.contains("fresh"));
    }
    @Test public void htmlCannotEvictExistingCache() {
        CatalogResolver.Result r = CatalogResolver.resolve(source, false, Collections.singletonList(attempt("blocked page", "<html>error</html>")), attempt("cache", list("old")), null);
        assertEquals(CatalogResolver.Origin.CACHE, r.origin);
    }
    @Test public void corruptCacheFallsBackToSeed() {
        CatalogResolver.Result r = CatalogResolver.resolve(source, true, Collections.emptyList(), attempt("cache", "invalid"), attempt("seed", list("seed")));
        assertEquals(CatalogResolver.Origin.BUNDLED, r.origin);
    }
    @Test public void releaseCatalogCanRefreshWhenRawIsBlocked() {
        CatalogResolver.Result r = CatalogResolver.resolve(source, false, Arrays.asList(blocked(), attempt("release", list("release"))), null, attempt("seed", list("seed")));
        assertEquals(CatalogResolver.Origin.NETWORK, r.origin); assertEquals("release", r.label);
    }
    @Test public void emptySourcesAreReportedRatherThanInvented() {
        CatalogResolver.Result r = CatalogResolver.resolve(source, false, Collections.singletonList(blocked()), null, null);
        assertEquals(CatalogResolver.Origin.NONE, r.origin); assertTrue(r.entries.isEmpty());
    }
    @Test public void customBestFanUrlDoesNotOptIntoBundledDefault() {
        assertFalse(CatalogResolver.isDefaultBestFan(new Source("best-fan", "custom", "https://example.com/custom.m3u", true, 0)));
        assertTrue(CatalogResolver.isDefaultBestFan(source));
    }
}
