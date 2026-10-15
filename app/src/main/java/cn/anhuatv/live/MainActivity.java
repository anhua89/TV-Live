package cn.anhuatv.live;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;
import cn.anhuatv.live.core.Models.Channel;
import cn.anhuatv.live.core.Models.Source;
import cn.anhuatv.live.core.Models.Stream;
import cn.anhuatv.live.core.PlaylistParser;
import cn.anhuatv.live.core.RouteOrder;
import cn.anhuatv.live.core.RouteOrder.Health;
import cn.anhuatv.live.core.WebChannelRoutes;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@UnstableApi
public final class MainActivity extends Activity {
    private static final int IMPORT_PLAYLIST = 101;
    private static final int WEB_CHANNEL = 102;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final List<Channel> channels = new ArrayList<>(), visible = new ArrayList<>();
    private final String[] categories = {"全部", "收藏", "央视", "卫视", "成都", "其他"};
    private Store store;
    private ChannelRepository repository;
    private LivePlayer player;
    private UpdateManager updates;
    private FrameLayout root;
    private LinearLayout drawer;
    private TextView osd, drawerTitle, empty;
    private ListView list;
    private ChannelAdapter adapter;
    private Future<?> refreshTask, probeTask;
    private AlertDialog frameDialog;
    private String loadSummary = "首次启动，正在获取频道…", digits = "";
    private int category, refreshGeneration, testGeneration;
    private long backAt, lastBrowseAt;
    private boolean destroyed, stopped, foreground, frameTesting;
    private boolean webChannelOpen;
    private String lastWebSelection = "";
    private Channel restoreChannel;
    private Stream restoreStream;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        store = new Store(this); repository = new ChannelRepository(this);
        createUi();
        updates = new UpdateManager(this);
        player = new LivePlayer(this, (PlayerView) root.getChildAt(0), store, new LivePlayer.Listener() {
            @Override public void status(String text) { showStatus(text, false); }
            @Override public void playing(Channel channel, Stream stream) {
                store.lastChannel(channel.id);
                showStatus(numberOf(channel) + "  " + channel.name + "\n" + stream.sourceName + " · " + channel.streams.size() + " 条线路", true);
            }
        });
        showStatus(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME + "\n正在读取本地频道目录。右键打开工具。", false);
        loadInitial();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    private void createUi() {
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        PlayerView video = (PlayerView) getLayoutInflater().inflate(R.layout.player, root, false);
        root.addView(video);
        osd = text("", 21, Color.WHITE); osd.setPadding(dp(24), dp(18), dp(24), dp(18));
        osd.setBackgroundColor(0xDC12202E);
        FrameLayout.LayoutParams info = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(osd, info);
        drawer = new LinearLayout(this); drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(0xF2101C2B); drawer.setPadding(dp(15), dp(16), dp(15), dp(10));
        FrameLayout.LayoutParams panel = new FrameLayout.LayoutParams(dp(382), -1, Gravity.START);
        drawerTitle = text("全部", 22, 0xFF64DCC5); drawerTitle.setPadding(dp(10), 0, 0, dp(8));
        drawer.addView(drawerTitle);
        TextView hint = text("← → 切换分组    长按 ↑ ↓ 连续滚动\n确定播放 · 返回关闭 · 长按确定收藏", 12, 0xFFA4B7C9);
        hint.setPadding(dp(10), 0, 0, dp(10)); drawer.addView(hint);
        list = new ListView(this); list.setDividerHeight(0); list.setItemsCanFocus(false);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE); list.setSelector(focusShape());
        adapter = new ChannelAdapter(); list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, position, id) -> choose(position));
        list.setOnItemLongClickListener((p, v, position, id) -> { favorite(visible.get(position)); return true; });
        drawer.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        empty = text("此分组暂无频道", 17, Color.WHITE); empty.setGravity(Gravity.CENTER); empty.setPadding(0, dp(24), 0, dp(24));
        drawer.addView(empty); list.setEmptyView(empty);
        drawer.setVisibility(View.GONE); root.addView(drawer, panel);
        setContentView(root);
        root.setFocusable(true); root.setFocusableInTouchMode(true); root.requestFocus();
    }

    private void loadInitial() {
        final int generation = ++refreshGeneration;
        refreshTask = worker.submit(() -> {
            try {
                List<Source> sources = store.sources();
                ChannelRepository.Result cached = repository.load(sources, true);
                if (!cached.channels.isEmpty()) ui(() -> { if (generation == refreshGeneration) applyCatalog(cached); });
                ChannelRepository.Result remote = repository.load(sources, false);
                ui(() -> { if (generation == refreshGeneration) applyCatalog(remote); });
            } catch (Exception e) { ui(() -> { if (generation == refreshGeneration) showStatus("频道读取失败：" + e.getMessage() + "\n右键 → 订阅管理", false); }); }
        });
    }
    private void refresh() {
        final int generation = ++refreshGeneration;
        if (refreshTask != null) refreshTask.cancel(true);
        showStatus("正在刷新启用的订阅…", false);
        refreshTask = worker.submit(() -> {
            try {
                ChannelRepository.Result result = repository.load(store.sources(), false);
                ui(() -> { if (generation == refreshGeneration) applyCatalog(result); });
            } catch (Exception e) { ui(() -> { if (generation == refreshGeneration) showStatus("刷新失败：" + e.getMessage(), false); }); }
        });
    }
    private void applyCatalog(ChannelRepository.Result result) {
        Channel before = player.channel(); channels.clear(); channels.addAll(result.channels); loadSummary = result.summary;
        updateVisible();
        if (webChannelOpen) return;
        if (channels.isEmpty()) {
            cancelFrameTest(false); player.clear(); showStatus(result.summary + "\n右键 → 订阅管理，可修改或导入列表。", false); return;
        }
        Channel after = find(before == null ? store.lastChannel() : before.id);
        if (after == null) after = channels.get(0);
        // A refresh or cold start should offer the web entry, not launch a website on its own.
        if (WebChannelRoutes.usesWeb(after)) { player.clear(); openDrawer(); return; }
        Stream old = player.stream();
        boolean routeStillEnabled = false;
        if (old != null) for (Stream s : after.streams) if (s.key().equals(old.key())) { routeStillEnabled = true; break; }
        if (before == null || !before.id.equals(after.id) || !routeStillEnabled) tune(after, null);
        else if (!foreground) stopped = true;
        else toast("订阅已刷新，共 " + channels.size() + " 个频道");
    }
    private Channel find(String id) { for (Channel c : channels) if (c.id.equals(id)) return c; return null; }
    private int numberOf(Channel c) { for (int i = 0; i < channels.size(); i++) if (channels.get(i).id.equals(c.id)) return i + 1; return 0; }
    private void tune(Channel c, Stream stream) {
        cancelFrameTest(false);
        if (WebChannelRoutes.usesWeb(c)) {
            if (!foreground || webChannelOpen) return;
            webChannelOpen = true; lastWebSelection = c.id; player.suspend();
            try {
                startActivityForResult(new Intent(this, WebChannelActivity.class).putExtra("channel_name", c.name), WEB_CHANNEL);
            } catch (Exception e) {
                webChannelOpen = false; player.resume(); show("网页无法打开", "请返回频道列表后重试。" + e.getMessage());
            }
            return;
        }
        if (!foreground) { store.lastChannel(c.id); stopped = true; return; }
        player.play(c, stream); adapter.notifyDataSetChanged();
    }

    private void updateVisible() {
        int selected = list.getSelectedItemPosition(); visible.clear(); Set<String> favorites = store.favorites();
        String g = categories[category];
        for (Channel c : channels) if (g.equals("全部") || (g.equals("收藏") ? favorites.contains(c.id) : g.equals(c.group))) visible.add(c);
        drawerTitle.setText("‹  " + g + "  ›     " + visible.size() + " 台"); adapter.notifyDataSetChanged();
        if (!visible.isEmpty()) list.setSelection(Math.max(0, Math.min(selected, visible.size() - 1)));
    }
    private void openDrawer() {
        updateVisible(); drawer.setVisibility(View.VISIBLE); list.requestFocus();
        Channel c = player.channel();
        int index = 0;
        for (int i = 0; i < visible.size(); i++) if (c != null && c.id.equals(visible.get(i).id)) index = i;
        list.setSelection(index); osd.setVisibility(View.GONE);
    }
    private void closeDrawer() { drawer.setVisibility(View.GONE); root.requestFocus(); }
    private void choose(int position) { if (position < 0 || position >= visible.size()) return; Channel c = visible.get(position); closeDrawer(); tune(c, null); }
    private void favorite(Channel c) { if (c == null) return; toast(store.toggleFavorite(c) ? "已收藏 " + c.name : "已取消收藏 " + c.name); updateVisible(); }
    private void step(int delta) {
        if (channels.isEmpty()) { openDrawer(); return; }
        int index = player.channel() == null ? 0 : Math.max(0, numberOf(player.channel()) - 1);
        tune(channels.get((index + delta + channels.size()) % channels.size()), null);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode(); boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (frameTesting && key == KeyEvent.KEYCODE_BACK && down) { cancelFrameTest(true); return true; }
        if (key == KeyEvent.KEYCODE_BACK) {
            if (down && event.getRepeatCount() == 0) {
                if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
                else if (backAt != 0 && SystemClock.elapsedRealtime() - backAt < 2000) finish();
                else { backAt = SystemClock.elapsedRealtime(); toast("再按一次返回键退出"); }
            }
            return true;
        }
        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
            if (down && event.getRepeatCount() == 0) {
                digits += (key - KeyEvent.KEYCODE_0); if (digits.length() > 4) digits = digits.substring(digits.length() - 4);
                showStatus("频道号 " + digits, false); main.removeCallbacks(numberTune); main.postDelayed(numberTune, 1100);
            }
            return true;
        }
        boolean center = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
        if (drawer.getVisibility() == View.VISIBLE) {
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (down && !visible.isEmpty()) {
                    long now = SystemClock.elapsedRealtime();
                    if (event.getRepeatCount() == 0 || now - lastBrowseAt >= 75) {
                        lastBrowseAt = now; int selected = Math.max(0, list.getSelectedItemPosition());
                        int delta = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                        // Holding only changes selection. A single OK key-up commits playback.
                        list.setSelection(Math.max(0, Math.min(visible.size() - 1, selected + delta)));
                    }
                }
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down && event.getRepeatCount() == 0) {
                    category = (category + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1) + categories.length) % categories.length;
                    updateVisible(); list.setSelection(0);
                }
                return true;
            }
            if (center) {
                if (!down) {
                    int p = list.getSelectedItemPosition();
                    if (event.getEventTime() - event.getDownTime() >= 600 && p >= 0 && p < visible.size()) favorite(visible.get(p));
                    else choose(p);
                }
                return true;
            }
        } else if (center) {
            if (!down) {
                if (event.getEventTime() - event.getDownTime() >= 600) favorite(player.channel()); else openDrawer();
            }
            return true;
        } else if (down && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (event.getRepeatCount() == 0) step(key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            else openDrawer();
            return true;
        } else if (key == KeyEvent.KEYCODE_DPAD_LEFT) { if (down && event.getRepeatCount() == 0) openDrawer(); return true; }
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { if (down && event.getRepeatCount() == 0) tools(); return true; }
        if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_SETTINGS) { if (down && event.getRepeatCount() == 0) tools(); return true; }
        if (key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            if (down && event.getRepeatCount() == 0) step(key == KeyEvent.KEYCODE_CHANNEL_UP ? -1 : 1); return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private final Runnable numberTune = () -> {
        try {
            int number = Integer.parseInt(digits); digits = "";
            if (number >= 1 && number <= channels.size()) { closeDrawer(); tune(channels.get(number - 1), null); }
            else showStatus("没有这个频道号；按确定查看列表编号。", true);
        } catch (NumberFormatException e) { digits = ""; }
    };

    private void tools() {
        closeDrawer();
        String[] labels = {"当前频道线路 / 手动换源", "本地网络测速（本频道全部线路）", "逐路出画实测（会临时切换画面）",
                "订阅管理", "刷新启用的订阅", "导入本地 M3U / TXT", "收藏 / 取消收藏当前频道", "重新连接当前频道",
                "检查应用更新", "使用说明与状态"};
        new AlertDialog.Builder(this).setTitle(getString(R.string.app_name) + " · 工具").setItems(labels, (d, which) -> {
            switch (which) {
                case 0: routes(); break;
                case 1: networkTest(); break;
                case 2: frameTest(); break;
                case 3: sources(); break;
                case 4: refresh(); break;
                case 5: importPlaylist(); break;
                case 6: favorite(player.channel()); break;
                case 7: if (freshCurrent() != null) tune(freshCurrent(), null); break;
                case 8: updates.check(); break;
                case 9: about(); break;
                default: break;
            }
        }).setNegativeButton("关闭", null).show();
    }
    private Channel freshCurrent() { Channel c = player.channel(); return c == null ? null : find(c.id); }
    private void routes() {
        Channel c = freshCurrent(); if (c == null) { toast("请先选择一个频道"); return; }
        List<Stream> ordered = RouteOrder.sorted(c.streams, store.metrics(c.streams), store.preferred(c), System.currentTimeMillis());
        String[] rows = new String[ordered.size()];
        for (int i = 0; i < rows.length; i++) rows[i] = (i + 1) + " · " + ordered.get(i).sourceName + "\n" + metric(ordered.get(i));
        new AlertDialog.Builder(this).setTitle(c.name + " · 选线路").setItems(rows, (d, index) -> {
            Stream chosen = ordered.get(index); store.prefer(c, chosen); tune(c, chosen);
        }).setNegativeButton("返回", null).show();
    }
    private String metric(Stream s) {
        Health h = store.metric(s);
        if (h == null) return "未测试；IP/运营商限制需本机验证";
        if (System.currentTimeMillis() - h.testedAt >= RouteOrder.VALID_FOR_MS) return "历史结果已超过 24 小时，建议复测";
        if (h.failed) return "最近探测或播放失败";
        return (h.networkMs >= 0 ? "抽样 " + h.networkMs + " ms" : "未测网络抽样") + " · " +
                (h.frameMs >= 0 ? "出画 " + h.frameMs + " ms" : "未验证出画");
    }
    private void networkTest() {
        Channel c = freshCurrent(); if (c == null) { toast("请先选择频道"); return; }
        if (probeTask != null && !probeTask.isDone()) { toast("已有测速任务在进行"); return; }
        TextView status = text("正在本机抽样媒体数据…\n此结果不代表成功解码。", 17, Color.WHITE); status.setPadding(dp(24), dp(16), dp(24), dp(16));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(c.name + " · 网络抽样")
                .setView(scrollable(status)).setNegativeButton("关闭 / 停止", null).create();
        dialog.setOnDismissListener(d -> { if (probeTask != null) probeTask.cancel(true); }); dialog.show();
        probeTask = worker.submit(() -> {
            StringBuilder lines = new StringBuilder("结果仅表示本机网络抽样，出画请单独实测。\n");
            for (int i = 0; i < c.streams.size(); i++) {
                if (Thread.currentThread().isInterrupted()) return;
                Stream s = c.streams.get(i);
                String progress = lines + "\n正在探测 " + (i + 1) + "/" + c.streams.size() + " · " + s.sourceName;
                ui(() -> { if (dialog.isShowing()) status.setText(progress); });
                try {
                    Net.Probe p = Net.probe(s); store.recordNetwork(s, p.elapsedMs, false);
                    lines.append(i + 1).append(" · ").append(s.sourceName).append("\n").append(p.elapsedMs).append(" ms · ").append(p.detail).append("\n\n");
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) return;
                    if (!(e instanceof Net.UnsupportedProbeException)) store.recordNetwork(s, -1, true);
                    lines.append(i + 1).append(" · ").append(s.sourceName).append(e instanceof Net.UnsupportedProbeException ? "\n未探测：" : "\n未通过：").append(e.getMessage()).append("\n\n");
                }
                String text = lines.toString(); ui(() -> { if (dialog.isShowing()) status.setText(text); });
            }
            String text = lines.append("测速结束。右键 → 线路 可查看排序。").toString();
            ui(() -> { if (dialog.isShowing()) status.setText(text); });
        });
    }

    private void frameTest() {
        Channel c = freshCurrent(); if (c == null) { toast("请先选择频道"); return; }
        cancelFrameTest(false); restoreChannel = c; restoreStream = player.stream(); frameTesting = true;
        final int generation = ++testGeneration;
        TextView status = text("将逐条尝试本频道线路，每条最多 12 秒。\n测试时静音；完成后恢复原频道。", 16, Color.WHITE);
        status.setPadding(dp(24), dp(16), dp(24), dp(16));
        frameDialog = new AlertDialog.Builder(this).setTitle(c.name + " · 实际出画测试")
                .setView(scrollable(status)).setNegativeButton("停止并恢复", null).create();
        frameDialog.setOnDismissListener(d -> cancelFrameTest(true)); frameDialog.show();
        runFrame(c, 0, new StringBuilder(), status, generation);
    }
    private void runFrame(Channel c, int index, StringBuilder lines, TextView status, int generation) {
        if (!frameTesting || generation != testGeneration) return;
        if (index >= c.streams.size()) {
            status.setText(lines + "\n测试完成，已恢复原频道。");
            frameTesting = false; player.cancelTest();
            if (restoreChannel != null && foreground) player.play(restoreChannel, restoreStream);
            return;
        }
        Stream s = c.streams.get(index); status.setText(lines + "\n正在测试 " + (index + 1) + "/" + c.streams.size() + " · " + s.sourceName);
        player.test(c, s, (ok, ms, detail) -> {
            if (!frameTesting || generation != testGeneration) return;
            lines.append(index + 1).append(" · ").append(s.sourceName).append("\n").append(ok ? "✓ " : "× ").append(detail).append("\n\n");
            main.post(() -> runFrame(c, index + 1, lines, status, generation));
        });
    }
    private void cancelFrameTest(boolean restore) {
        boolean wasTesting = frameTesting; frameTesting = false; testGeneration++;
        if (player != null) player.cancelTest();
        if (frameDialog != null) { AlertDialog old = frameDialog; frameDialog = null; old.setOnDismissListener(null); old.dismiss(); }
        if (wasTesting && restore && restoreChannel != null && foreground) player.play(restoreChannel, restoreStream);
    }

    private void sources() {
        List<Source> all = store.sources(); String[] names = new String[all.size() + 1];
        for (int i = 0; i < all.size(); i++) names[i] = (all.get(i).enabled ? "[开] " : "[关] ") + all.get(i).name;
        names[all.size()] = "+ 添加网络订阅";
        new AlertDialog.Builder(this).setTitle("订阅管理（改动后自动刷新）").setItems(names, (d, i) -> {
            if (i == all.size()) editSource(null); else sourceOptions(all.get(i));
        }).setNegativeButton("返回", null).show();
    }
    private void sourceOptions(Source source) {
        String[] items = {source.enabled ? "停用此订阅" : "启用此订阅", "修改名称 / 地址", "查看地址", "删除此订阅"};
        new AlertDialog.Builder(this).setTitle(source.name).setItems(items, (d, i) -> {
            if (i == 0) { saveSource(source.enabled(!source.enabled)); refresh(); }
            else if (i == 1) editSource(source);
            else if (i == 2) show(source.name, source.url + "\n优先级 " + source.priority + "（数值越小越优先）");
            else new AlertDialog.Builder(this).setTitle("删除订阅？").setMessage(source.name)
                    .setPositiveButton("删除", (v, w) -> { List<Source> all = store.sources(); for (int n = all.size() - 1; n >= 0; n--) if (all.get(n).id.equals(source.id)) all.remove(n); store.saveSources(all); refresh(); })
                    .setNegativeButton("取消", null).show();
        }).setNegativeButton("返回", null).show();
    }
    private void editSource(Source old) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(24), dp(8), dp(24), dp(8));
        EditText name = new EditText(this); name.setHint("订阅名称"); name.setSingleLine(true); if (old != null) name.setText(old.name); form.addView(name);
        EditText url = new EditText(this); url.setHint("https://…/list.m3u"); url.setSingleLine(true); url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (old != null) url.setText(old.url); form.addView(url);
        EditText priority = new EditText(this); priority.setHint("优先级，越小越优先"); priority.setInputType(InputType.TYPE_CLASS_NUMBER); priority.setText(String.valueOf(old == null ? 60 : old.priority)); form.addView(priority);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(old == null ? "添加订阅" : "修改订阅").setView(form)
                .setPositiveButton("保存并刷新", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = name.getText().toString().trim(), address = url.getText().toString().trim();
            if (title.isEmpty() || !(address.startsWith("http://") || address.startsWith("https://") ||
                    (old != null && address.equals(old.url) && (address.startsWith("asset://") || address.startsWith("local://"))))) { toast("请填写名称和 HTTP/HTTPS 订阅地址"); return; }
            int rank;
            try { rank = Integer.parseInt(priority.getText().toString()); } catch (Exception e) { toast("优先级请输入整数"); return; }
            saveSource(new Source(old == null ? UUID.randomUUID().toString() : old.id, title, address, old == null || old.enabled, rank));
            dialog.dismiss(); refresh();
        })); dialog.show();
    }
    private void saveSource(Source source) {
        List<Source> all = store.sources(); boolean found = false;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(source.id)) { all.set(i, source); found = true; break; }
        if (!found) all.add(source); store.saveSources(all);
    }
    private void importPlaylist() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, IMPORT_PLAYLIST);
        } catch (Exception e) { show("电视上没有文件选择器", "可使用右键 → 订阅管理添加网址，或在打包前修改 app/src/main/assets/sources.json。"); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == WEB_CHANNEL) {
            webChannelOpen = false; openDrawer();
            for (int i = 0; i < visible.size(); i++) if (visible.get(i).id.equals(lastWebSelection)) { list.setSelection(i); break; }
            return;
        }
        if (request != IMPORT_PLAYLIST || result != RESULT_OK || data == null || data.getData() == null) return;
        worker.submit(() -> {
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                if (in == null) throw new java.io.IOException("文件不可读");
                String text = Net.decode(Net.read(in, 8 * 1024 * 1024));
                Source s = new Source(UUID.randomUUID().toString(), "本地导入 " + new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new java.util.Date()), "local://import", true, 60);
                if (PlaylistParser.parse(text, s).isEmpty()) throw new java.io.IOException("未识别到频道；请选 M3U 或 频道名,地址 格式的 TXT");
                java.io.File dir = new java.io.File(getFilesDir(), "imports"); if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("目录不可写");
                java.io.File file = new java.io.File(dir, s.id + ".m3u");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                Source saved = new Source(s.id, s.name, "local://" + file.getName(), true, 60);
                ui(() -> { saveSource(saved); refresh(); });
            } catch (Exception e) { ui(() -> show("导入失败", String.valueOf(e.getMessage()))); }
        });
    }
    private void about() {
        show(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME + " · 使用说明",
                "上下：切台；按住进入频道列表继续滚动\n确定 / 左键：频道列表；列表中左右切换分组\n长按确定：收藏当前或选中频道\n右键 / 菜单：线路、测速、订阅、更新\n数字键：输入列表编号后自动跳台\n返回：关闭列表；连续两次退出\n\n" +
                "成都台：选择成都频道后打开网页，在网页中选择对应频道；菜单键可修改网页地址。其他频道仍使用原生播放器。\n" +
                "回看：本版未提供 EPG 或回看播放；已保留订阅中的 catchup 元数据。网页是否提供回看由网站决定。\n\n" +
                "更新仓库：" + (BuildConfig.UPDATE_REPOSITORY.isEmpty() ? "未配置" : BuildConfig.UPDATE_REPOSITORY) + "\n\n" + loadSummary);
    }

    private void showStatus(String text, boolean hideLater) {
        if (destroyed) return; osd.setText(text); osd.setVisibility(View.VISIBLE); main.removeCallbacks(hideOsd);
        if (hideLater) main.postDelayed(hideOsd, 4500);
    }
    private final Runnable hideOsd = () -> osd.setVisibility(View.GONE);
    private void toast(String text) { if (!destroyed) Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private void show(String title, String text) { if (!destroyed && !isFinishing()) new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("确定", null).show(); }
    private void ui(Runnable action) { main.post(() -> { if (!destroyed && !isFinishing()) action.run(); }); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private ScrollView scrollable(View child) { ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(child); return scroll; }
    private TextView text(String text, int size, int color) { TextView v = new TextView(this); v.setText(text); v.setTextColor(color); v.setTextSize(size); return v; }
    private GradientDrawable focusShape() { GradientDrawable g = new GradientDrawable(); g.setColor(0xFF205548); g.setCornerRadius(dp(9)); return g; }

    private final class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convert, ViewGroup parent) {
            TextView row = convert instanceof TextView ? (TextView) convert : text("", 18, Color.WHITE);
            Channel c = visible.get(position); boolean playing = player != null && player.channel() != null && player.channel().id.equals(c.id);
            String name = c.name + (WebChannelRoutes.usesWeb(c) && !c.name.contains("网页") ? " · 网页" : "");
            row.setText(String.format(Locale.ROOT, "%03d   %s%s", numberOf(c), name, store.favorites().contains(c.id) ? "  ★" : ""));
            row.setTextColor(playing ? 0xFF64DCC5 : Color.WHITE); row.setTypeface(null, playing ? Typeface.BOLD : Typeface.NORMAL);
            row.setPadding(dp(12), dp(12), dp(8), dp(12)); row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END); return row;
        }
    }
    @Override protected void onResume() {
        super.onResume(); foreground = true; immersive(); if (updates != null) updates.onResume();
        if (stopped && player != null) {
            stopped = false; Channel c = find(store.lastChannel());
            if (WebChannelRoutes.usesWeb(c)) openDrawer();
            else if (c != null) player.play(c, null); else player.resume();
        }
    }
    @Override protected void onStop() {
        foreground = false; stopped = true; cancelFrameTest(false); if (player != null) player.suspend(); super.onStop();
    }
    @Override protected void onDestroy() {
        destroyed = true; cancelFrameTest(false); refreshGeneration++; worker.shutdownNow(); main.removeCallbacksAndMessages(null);
        if (player != null) player.release(); if (updates != null) updates.close(); super.onDestroy();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive(); }
}
