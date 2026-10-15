package cn.anhuatv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.Uri;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import cn.anhuatv.live.core.Models.Channel;
import cn.anhuatv.live.core.Models.Stream;
import cn.anhuatv.live.core.RouteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

@UnstableApi
public final class LivePlayer {
    public interface Listener {
        void status(String text);
        void playing(Channel channel, Stream stream);
    }
    public interface FrameTest { void finished(boolean success, long elapsedMs, String detail); }
    private final Context context;
    private final PlayerView view;
    private final Store store;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExoPlayer player;
    private final Set<String> tried = new HashSet<>();
    private Channel channel;
    private Stream current;
    private List<Stream> order = new ArrayList<>();
    private int attempt;
    private String mediaId = "";
    private long started, waitingSince, lastPosition, positionChangedAt;
    private boolean rendered, active, released, failing;
    private FrameTest testCallback;
    private final Timeline.Window window = new Timeline.Window();

    public LivePlayer(Context context, PlayerView view, Store store, Listener listener) {
        this.context = context; this.view = view; this.store = store; this.listener = listener;
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1800, 9000, 300, 900).build();
        player = new ExoPlayer.Builder(context,
                new DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                .setLoadControl(load).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        view.setPlayer(player); view.setUseController(false);
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onRenderedFirstFrame(EventTime time, Object output, long renderTimeMs) {
                if (!active || released || time.timeline.isEmpty() || time.windowIndex < 0 || time.windowIndex >= time.timeline.getWindowCount()) return;
                String id = time.timeline.getWindow(time.windowIndex, window).mediaItem.mediaId;
                if (!id.equals(mediaId)) return;
                rendered = true; waitingSince = 0;
                long elapsed = SystemClock.elapsedRealtime() - started;
                store.recordFrame(current, elapsed, false);
                if (testCallback != null) completeTest(true, elapsed, "已出画 " + elapsed + " ms");
                else { store.prefer(channel, current); listener.playing(channel, current); }
            }
        });
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                if (active) fail("播放错误 " + error.getErrorCodeName());
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (!active) return;
                if (state == Player.STATE_ENDED) fail("直播已结束或线路中断");
                else if (state == Player.STATE_BUFFERING && waitingSince == 0) waitingSince = SystemClock.elapsedRealtime();
                else if (state == Player.STATE_READY && rendered) waitingSince = 0;
            }
        });
        main.post(watchdog);
    }

    public void play(Channel c, Stream force) {
        cancelTest(); channel = c; tried.clear();
        order = RouteOrder.sorted(c.streams, store.metrics(c.streams), store.preferred(c), System.currentTimeMillis());
        if (force != null) { order.remove(force); order.add(0, force); }
        next();
    }
    public Channel channel() { return channel; }
    public Stream stream() { return current; }
    public boolean isTesting() { return testCallback != null; }

    private void next() {
        for (Stream s : order) {
            if (!tried.contains(s.key())) { tried.add(s.key()); start(s); return; }
        }
        active = false; player.stop();
        listener.status("本频道全部线路均未能继续播放。右键可测速、换源或重试；上下键可换台。");
    }

    private void start(Stream stream) {
        active = false; player.stop(); player.clearMediaItems();
        current = stream; attempt++; mediaId = channel.id + ":" + attempt;
        started = SystemClock.elapsedRealtime(); waitingSince = started;
        rendered = false; failing = false; lastPosition = 0; positionChangedAt = started;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Net.USER_AGENT).setConnectTimeoutMs(5000).setReadTimeoutMs(5000)
                .setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(stream.headers);
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(new DefaultDataSource.Factory(context, http));
        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(stream.url)).setMediaId(mediaId);
        String path = Uri.parse(stream.url).getPath();
        if (path != null) {
            String lower = path.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) item.setMimeType("application/x-mpegURL");
            else if (lower.endsWith(".mpd")) item.setMimeType("application/dash+xml");
        }
        active = true;
        try {
            player.setMediaSource(factory.createMediaSource(item.build()));
            player.prepare(); player.setPlayWhenReady(true);
            listener.status(channel.name + " · " + stream.sourceName + "\n连接线路 " + tried.size() + "/" + order.size());
        } catch (Exception e) { fail("线路格式或设备解码不支持"); }
    }
    private void fail(String reason) {
        if (!active || released || failing) return;
        failing = true;
        store.recordFrame(current, -1, true);
        if (testCallback != null) { completeTest(false, SystemClock.elapsedRealtime() - started, reason); return; }
        listener.status(reason + "，尝试下一条线路…");
        int generation = attempt;
        main.post(() -> { if (active && !released && generation == attempt && failing) next(); });
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (released) return;
            if (active && player.getPlayWhenReady()) {
                long now = SystemClock.elapsedRealtime();
                if (!rendered && now - started > 12000) fail("12 秒内未出画");
                else if (waitingSince != 0 && now - waitingSince > 10000) fail("连续缓冲超时");
                if (rendered && player.getPlaybackState() == Player.STATE_READY && player.isPlaying()) {
                    long p = player.getCurrentPosition();
                    if (Math.abs(p - lastPosition) > 100) { lastPosition = p; positionChangedAt = now; }
                    else if (now - positionChangedAt > 15000) fail("播放进度停止");
                } else positionChangedAt = now;
            }
            main.postDelayed(this, 500);
        }
    };

    /** Uses the visible TV player; audio is muted while testing and normal playback is restored by the UI. */
    public void test(Channel c, Stream s, FrameTest callback) {
        cancelTest(); channel = c; testCallback = callback; order = new ArrayList<>(); order.add(s);
        tried.clear(); tried.add(s.key()); player.setVolume(0); start(s);
    }
    private void completeTest(boolean ok, long ms, String detail) {
        FrameTest callback = testCallback; testCallback = null; active = false; player.stop(); player.setVolume(1);
        if (callback != null) callback.finished(ok, ms, detail);
    }
    public void cancelTest() {
        if (testCallback != null) { testCallback = null; active = false; player.stop(); player.setVolume(1); }
    }
    public void suspend() { cancelTest(); active = false; player.stop(); }
    public void clear() { suspend(); channel = null; current = null; order.clear(); tried.clear(); }
    public void resume() { if (channel != null) play(channel, current); }
    public void release() { released = true; active = false; testCallback = null; main.removeCallbacksAndMessages(null); view.setPlayer(null); player.release(); }
}
