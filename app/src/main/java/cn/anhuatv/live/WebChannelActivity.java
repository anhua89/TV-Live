package cn.anhuatv.live;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import cn.anhuatv.live.core.WebChannelRoutes;
import cn.anhuatv.live.core.WebPagePolicy;
import java.io.InputStream;
import org.json.JSONObject;

/** A separate, optional page for Chengdu TV; the native player is stopped by its owner. */
public final class WebChannelActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private FrameLayout content;
    private LinearLayout toolbar;
    private TextView status, location;
    private Pointer pointer;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private String pageUrl, resumeUrl;
    private String adapterScript = "", defaultAgent = "", lastIssue = "", lastScriptIssue = "";
    private JSONObject videoState = new JSONObject();
    private WebView popup;
    private boolean pageFailed, destroyed, foreground, pageReady, desktopMode;
    private int pageGeneration;
    private long loadedAt;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        String saved = getPreferences(MODE_PRIVATE).getString("page_url", WebChannelRoutes.DEFAULT_URL);
        pageUrl = WebPagePolicy.startUrl(saved);
        if (!pageUrl.equals(saved)) getPreferences(MODE_PRIVATE).edit().putString("page_url", pageUrl).apply();
        desktopMode = getPreferences(MODE_PRIVATE).getBoolean("desktop_mode", true);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setBackgroundColor(Color.BLACK);
        toolbar = new LinearLayout(this); toolbar.setPadding(dp(10), dp(4), dp(10), dp(4)); toolbar.setBackgroundColor(0xFF12202E);
        addButton("频道列表", this::finish);
        addButton("看度直播", () -> navigate(WebChannelRoutes.DEFAULT_URL));
        addButton("播放 / 全屏", this::playOptions);
        addButton("网页工具", this::tools);
        page.addView(toolbar);
        status = new TextView(this); status.setTextColor(Color.WHITE); status.setTextSize(14);
        status.setPadding(dp(14), dp(5), dp(14), dp(5)); status.setMaxLines(3); status.setBackgroundColor(0xFF12202E); page.addView(status);
        location = new TextView(this); location.setTextColor(0xFFBFD6E7); location.setTextSize(11);
        location.setPadding(dp(14), 0, dp(14), dp(4)); location.setSingleLine(true);
        location.setEllipsize(android.text.TextUtils.TruncateAt.END); location.setBackgroundColor(0xFF12202E); page.addView(location);
        content = new FrameLayout(this); content.setFocusable(true); content.setFocusableInTouchMode(true);
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(page);
        pointer = new Pointer();
        try { createWeb(); }
        catch (RuntimeException | LinkageError e) {
            if (web != null) {
                if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
                web.destroy(); web = null;
            }
            message("电视的网页组件不可用。可按菜单 → 在电视浏览器打开；也可返回频道列表。");
        }
        if (web != null) content.post(this::loadPage);
        else toolbar.getChildAt(3).requestFocus();
    }

    private void addButton(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setTextSize(16);
        button.setOnClickListener(v -> action.run());
        toolbar.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWeb() {
        web = new WebView(this); web.setBackgroundColor(Color.BLACK);
        defaultAgent = web.getSettings().getUserAgentString();
        try (InputStream in = getAssets().open("cdtv_tv.js")) { adapterScript = Net.decode(Net.read(in, 128 * 1024)); }
        catch (Exception e) { throw new IllegalStateException("网页适配文件缺失", e); }
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setUserAgentString(desktopMode ? WebPagePolicy.desktopUserAgent(defaultAgent) : defaultAgent);
        settings.setTextZoom(100); settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(true); settings.setBuiltInZoomControls(true); settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // No native Javascript interface, account, proxy, or extraction of media URLs.
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blockNavigation(url);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return blockNavigation(request.getUrl().toString());
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if ("about:blank".equals(url)) return;
                pageGeneration++; pageReady = false; lastIssue = ""; lastScriptIssue = ""; videoState = new JSONObject();
                main.removeCallbacks(videoPoll); closePopup(); applyPageSettings(url);
                pageFailed = false; message("正在打开看度网页… 菜单键可打开工具；返回键回到频道列表。");
                main.removeCallbacks(slowPage); main.postDelayed(slowPage, 20000);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if ("about:blank".equals(url)) return;
                main.removeCallbacks(slowPage);
                if (!pageFailed && url.equals(view.getUrl())) {
                    pageReady = true; loadedAt = SystemClock.elapsedRealtime(); installAdapter();
                    message("页面已载入，正在检查视频。可用光标选择 CDTV 频道，或按“播放 / 全屏”。");
                }
            }
            @Override public void onReceivedError(WebView view, int code, String description, String url) {
                if (Build.VERSION.SDK_INT < 23) pageError("网页连接失败：" + description);
            }
            @TargetApi(23)
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) pageError("网页连接失败：" + error.getDescription());
                else lastIssue = "子资源连接失败：" + WebPagePolicy.displayUrl(request.getUrl().toString());
            }
            @TargetApi(23)
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) pageError("网站返回 HTTP " + response.getStatusCode());
                else lastIssue = "子资源 HTTP " + response.getStatusCode() + "：" + WebPagePolicy.displayUrl(request.getUrl().toString());
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel(); pageError("网页证书校验失败");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage msg) {
                if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    String line = msg.message();
                    lastScriptIssue = line.contains("Mixed Content") ? "网页报告混合内容被拦截" :
                            line.contains("NotSupportedError") ? "网页报告格式或功能不支持" :
                            line.contains("NotAllowedError") ? "网页拒绝自动播放" : "网页脚本错误（第 " + msg.lineNumber() + " 行）";
                }
                return false;
            }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message message) {
                if (!gesture || destroyed || !foreground) return false;
                closePopup();
                popup = new WebView(WebChannelActivity.this);
                popup.getSettings().setJavaScriptEnabled(true);
                popup.getSettings().setUserAgentString(web.getSettings().getUserAgentString());
                popup.getSettings().setAllowFileAccess(false); popup.getSettings().setAllowContentAccess(false);
                final WebView child = popup;
                popup.setWebViewClient(new WebViewClient() {
                    private boolean relayed;
                    private boolean relay(String url) {
                        if ("about:blank".equals(url)) return false;
                        if (relayed) return true;
                        relayed = true;
                        main.post(() -> {
                            if (popup != child || destroyed || !foreground) return;
                            closePopup();
                            if (WebChannelRoutes.validPageUrl(url)) navigate(url); else blockNavigation(url);
                        });
                        return true;
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return relay(url); }
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) { return relay(request.getUrl().toString()); }
                    @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) { relay(url); }
                });
                content.addView(popup, 0, new FrameLayout.LayoutParams(1, 1));
                ((WebView.WebViewTransport) message.obj).setWebView(popup); message.sendToTarget();
                main.postDelayed(popupTimeout, 10000); return true;
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreen != null) { callback.onCustomViewHidden(); return; }
                fullscreen = view; fullscreenCallback = callback;
                web.setVisibility(View.GONE); toolbar.setVisibility(View.GONE); status.setVisibility(View.GONE); location.setVisibility(View.GONE);
                content.addView(view, new FrameLayout.LayoutParams(-1, -1));
                pointer.bringToFront(); content.requestFocus(); immersive();
            }
            @Override public void onHideCustomView() { leaveFullscreen(); }
        });
        content.addView(web, new FrameLayout.LayoutParams(-1, -1));
        content.addView(pointer, new FrameLayout.LayoutParams(-1, -1));
        content.requestFocus();
    }

    private void loadPage() {
        if (web == null) { message("网页组件不可用，请从网页工具使用电视浏览器打开。"); return; }
        navigate(pageUrl);
    }
    private void navigate(String url) {
        if (web == null || destroyed || !WebChannelRoutes.validPageUrl(url)) return;
        resumeUrl = null; leaveFullscreen(); web.stopLoading(); applyPageSettings(url); web.loadUrl(url); content.requestFocus();
    }
    private void applyPageSettings(String url) {
        boolean official = WebPagePolicy.isCditv(url);
        // Only the requested official site gets legacy HTTP-video compatibility.
        web.getSettings().setMixedContentMode(official ? WebSettings.MIXED_CONTENT_ALWAYS_ALLOW : WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, official);
        web.getSettings().setTextZoom(100);
        web.setInitialScale(desktopMode && official && web.getWidth() > 0 ? Math.max(1, Math.round(web.getWidth() * 100f / 1280f)) : 0);
        location.setText((desktopMode ? "桌面布局 · " : "兼容布局 · ") + WebPagePolicy.displayUrl(url));
    }
    private boolean blockNavigation(String url) {
        if (WebChannelRoutes.validPageUrl(url)) return false;
        if (!"about:blank".equals(url)) {
            lastIssue = "该链接要求打开外部应用，未提供普通网页地址";
            message("这个链接要求打开外部 App。请回到看度首页，选择“电视直播”中的 CDTV 频道。");
        }
        return true;
    }
    private final Runnable popupTimeout = () -> {
        if (popup != null) { closePopup(); message("新窗口未提供可打开的网页。可回到看度直播首页，或按菜单查看网页诊断。"); }
    };
    private void closePopup() {
        main.removeCallbacks(popupTimeout);
        if (popup == null) return;
        WebView old = popup; popup = null; old.stopLoading();
        if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
        old.destroy();
    }
    private void installAdapter() {
        if (web == null || !pageReady || destroyed) return;
        final int generation = pageGeneration;
        boolean adapt = desktopMode && WebPagePolicy.isCditv(web.getUrl());
        web.evaluateJavascript(adapterScript + (adapt ? "\nwindow.__jianshiTv.desktop();" : ""), value -> {
            if (destroyed || web == null || generation != pageGeneration || !foreground) return;
            main.removeCallbacks(videoPoll); main.post(videoPoll);
        });
    }
    private final Runnable videoPoll = new Runnable() {
        @Override public void run() {
            if (destroyed || !foreground || !pageReady || pageFailed || web == null) return;
            final int generation = pageGeneration;
            String locate = WebPagePolicy.isHome(web.getUrl()) && SystemClock.elapsedRealtime() - loadedAt < 15000
                    ? "window.__jianshiTv.focusLive(0);" : "";
            web.evaluateJavascript("(function(){if(!window.__jianshiTv)return null;" + locate + "return window.__jianshiTv.snapshot();})()", value -> {
                if (destroyed || !foreground || web == null || generation != pageGeneration || pageFailed) return;
                try { videoState = new JSONObject(value); updateVideoStatus(); }
                catch (Exception e) { message("网页视频状态暂不可读取。可用光标点击网页播放按钮；菜单 → 网页诊断。"); }
                main.postDelayed(this, 1800);
            });
        }
    };
    private void updateVideoStatus() {
        int error = videoState.optInt("error"), count = videoState.optInt("videos");
        if (error > 0) {
            String reason = error == 1 ? "播放被中止" : error == 2 ? "媒体网络请求失败" : error == 3 ? "视频解码失败" : "媒体格式或来源不支持";
            message(reason + "。可按“播放 / 全屏”重试，或菜单 → 网页诊断 / 电视浏览器。");
        } else if (videoState.optBoolean("moving")) {
            message("检测到网页视频进度推进。方向键移光标，确定点击；菜单键打开工具。");
        } else if (!videoState.optString("playError").isEmpty()) {
            message("网页未接受播放或全屏请求。请用光标点击网页内的播放 / 全屏按钮；菜单可查看诊断。");
        } else if (SystemClock.elapsedRealtime() - loadedAt < 12000) {
            message("页面已载入，正在等待视频。可在“电视直播”中选台，再按“播放 / 全屏”。");
        } else if (count > 0) {
            message("视频尚未开始或正在等待数据。按“播放 / 全屏”尝试播放；菜单 → 网页诊断可查看错误。");
        } else if (videoState.optInt("blockedFrames") > 0) {
            message("播放器位于独立网页，暂不能读取视频状态。请用光标点击播放；菜单可使用电视浏览器。");
        } else {
            message("当前页面未检测到视频。点顶部“看度直播”回到首页电视直播区，或菜单查看网页诊断。");
        }
    }
    private void videoAction(String action) {
        if (web == null) return;
        web.evaluateJavascript(adapterScript + "\nwindow.__jianshiTv." + action + "();", value -> {
            try { message(new JSONObject(value).optString("detail", "请用光标点击网页内的播放按钮。")); }
            catch (Exception e) { message("网页未能响应，请用光标点击网页播放器，或查看网页诊断。"); }
        });
    }
    private void playOptions() {
        new AlertDialog.Builder(this).setTitle("网页视频").setItems(new String[]{"尝试播放", "网页视频全屏"},
                (d, i) -> videoAction(i == 0 ? "play" : "fullscreen")).setNegativeButton("返回", null).show();
    }
    private void resetLayout() {
        if (web == null) return;
        applyPageSettings(web.getUrl()); installAdapter();
        if (desktopMode && WebPagePolicy.isCditv(web.getUrl()) && web.getWidth() > 0) {
            float factor = (web.getWidth() / 1280f) / web.getScale();
            if (factor > 0 && !Float.isNaN(factor) && !Float.isInfinite(factor)) web.zoomBy(Math.max(0.01f, Math.min(100f, factor)));
        }
    }
    private void diagnostics() {
        String current = web == null ? pageUrl : web.getUrl();
        String detail = "页面：" + WebPagePolicy.displayUrl(current) + "\n标题：" + videoState.optString("title", "未读取") +
                "\n布局：" + (desktopMode ? "桌面" : "兼容") + "；网页宽度：" + videoState.optInt("width") +
                "\n视频数：" + videoState.optInt("videos") + "；内嵌页面：" + videoState.optInt("iframes") + "；不可读取：" + videoState.optInt("blockedFrames") +
                "\n视频就绪：" + videoState.optInt("ready") + "；暂停：" + videoState.optBoolean("paused", true) +
                "\n视频进度：" + videoState.optDouble("time", -1) + "；视频帧：" + videoState.optLong("frames", -1) +
                "\n媒体错误码：" + videoState.optInt("error") + "；播放提示：" + videoState.optString("playError", "") +
                "\n网络提示：" + (lastIssue.isEmpty() ? "未记录" : lastIssue) + "\n脚本提示：" + (lastScriptIssue.isEmpty() ? "未记录" : lastScriptIssue) +
                "\n浏览器内核：" + defaultAgent + "\n\n视频进度推进也不等于画面内容、声音和持续稳定性均已通过。";
        new AlertDialog.Builder(this).setTitle("网页诊断（可拍照反馈）").setMessage(detail)
                .setPositiveButton("关闭", null).setNeutralButton("电视浏览器", (d, i) -> openBrowser()).show();
    }
    private void pageError(String detail) {
        pageFailed = true; pageReady = false; lastIssue = detail; main.removeCallbacks(slowPage); main.removeCallbacks(videoPoll);
        message(detail + "。菜单键可重试、修改网址或用电视浏览器打开；返回键回到频道列表。");
    }
    private final Runnable slowPage = () -> message("网页加载较慢。菜单键可刷新、修改网址或使用电视浏览器；返回键回到频道列表。");
    private void message(String text) { if (!destroyed) status.setText(text); }

    private void tools() {
        String[] labels = {"看度首页电视直播", "定位电视直播区", "尝试播放网页视频", "网页视频全屏", "重新适配屏幕", "放大网页", "缩小网页",
                desktopMode ? "切换兼容布局" : "切换桌面布局", "刷新当前网页", "修改成都网页地址", "网页诊断", "在电视浏览器打开", "网页返回上一页", "返回频道列表"};
        new AlertDialog.Builder(this).setTitle("成都电视台 · 网页工具").setItems(labels, (d, i) -> {
            switch (i) {
                case 0: navigate(WebChannelRoutes.DEFAULT_URL); break;
                case 1: if (web != null) web.evaluateJavascript(adapterScript + "\nwindow.__jianshiTv.focusLive(0,true);", null); break;
                case 2: videoAction("play"); break;
                case 3: videoAction("fullscreen"); break;
                case 4: resetLayout(); break;
                case 5: if (web != null) web.zoomIn(); break;
                case 6: if (web != null) web.zoomOut(); break;
                case 7:
                    desktopMode = !desktopMode; getPreferences(MODE_PRIVATE).edit().putBoolean("desktop_mode", desktopMode).apply();
                    if (web != null) {
                        String url = WebChannelRoutes.validPageUrl(web.getUrl()) ? web.getUrl() : pageUrl;
                        web.getSettings().setUserAgentString(desktopMode ? WebPagePolicy.desktopUserAgent(defaultAgent) : defaultAgent); navigate(url);
                    }
                    break;
                case 8: if (web != null) navigate(WebChannelRoutes.validPageUrl(web.getUrl()) ? web.getUrl() : pageUrl); break;
                case 9: editUrl(); break;
                case 10: diagnostics(); break;
                case 11: openBrowser(); break;
                case 12: if (web != null && web.canGoBack()) web.goBack(); else toast("没有上一页"); break;
                case 13: finish(); break;
                default: break;
            }
        }).setNegativeButton("继续网页", null).show();
    }
    private void editUrl() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(pageUrl);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("成都频道共用的网页地址")
                .setMessage("填写你确认能观看的 HTTP/HTTPS 页面。只影响成都频道；可在网页内选择对应 CDTV 频道。")
                .setView(input).setPositiveButton("保存并打开", null)
                .setNeutralButton("恢复默认", (d, i) -> saveUrl(WebChannelRoutes.DEFAULT_URL))
                .setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = input.getText().toString().trim();
            if (!WebChannelRoutes.validPageUrl(url)) { input.setError("请输入完整的 HTTP/HTTPS 网页地址"); return; }
            saveUrl(url); dialog.dismiss();
        })); dialog.show();
    }
    private void saveUrl(String url) {
        pageUrl = url; getPreferences(MODE_PRIVATE).edit().putString("page_url", url).apply(); loadPage();
    }
    private void openBrowser() {
        String current = web == null ? null : web.getUrl();
        String url = WebChannelRoutes.validPageUrl(current) ? current : pageUrl;
        try {
            // Pause first, so a browser launch cannot leave two audio players running.
            if (web != null) web.onPause();
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (RuntimeException e) {
            if (web != null) web.onResume();
            new AlertDialog.Builder(this).setTitle("未能打开电视浏览器")
                    .setMessage("电视可能没有可用浏览器。可继续使用当前网页，或修改地址后重试。")
                    .setPositiveButton("确定", null).show();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode(); boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (key == KeyEvent.KEYCODE_BACK) {
            if (down && event.getRepeatCount() == 0) { if (fullscreen != null) leaveFullscreen(); else finish(); }
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_SETTINGS) {
            if (down && event.getRepeatCount() == 0) tools(); return true;
        }
        if (toolbar.hasFocus()) return super.dispatchKeyEvent(event);
        if (web != null && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN ||
                key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (down) pointer.move(key, event.getRepeatCount()); return true;
        }
        if (web != null && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) {
            if (!down) clickPointer(); return true;
        }
        if (web != null && (key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_CHANNEL_DOWN ||
                key == KeyEvent.KEYCODE_PAGE_UP || key == KeyEvent.KEYCODE_PAGE_DOWN)) {
            if (down) { if (key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_PAGE_UP) web.pageUp(false); else web.pageDown(false); }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private void clickPointer() {
        View target = fullscreen == null ? web : fullscreen;
        if (target == null) return;
        long now = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, pointer.x, pointer.y, 0);
        MotionEvent release = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, pointer.x, pointer.y, 0);
        try { target.dispatchTouchEvent(press); target.dispatchTouchEvent(release); }
        finally { press.recycle(); release.recycle(); }
        content.requestFocus();
    }
    private final class Pointer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x, y;
        Pointer() { super(WebChannelActivity.this); setFocusable(false); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { x = w / 2f; y = h / 2f; }
        void move(int key, int repeat) {
            int step = dp(repeat > 5 ? 28 : 12);
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) x -= step;
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) x += step;
            if (key == KeyEvent.KEYCODE_DPAD_UP) y -= step;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) y += step;
            if (fullscreen == null && web != null && (y < 0 || y > getHeight())) web.scrollBy(0, y < 0 ? -dp(55) : dp(55));
            x = Math.max(2, Math.min(getWidth() - 2, x)); y = Math.max(2, Math.min(getHeight() - 2, y)); invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xD9E94646); canvas.drawCircle(x, y, dp(7), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(Color.WHITE); canvas.drawCircle(x, y, dp(8), paint);
        }
    }
    private void leaveFullscreen() {
        if (fullscreen == null) return;
        content.removeView(fullscreen); fullscreen = null;
        WebChromeClient.CustomViewCallback callback = fullscreenCallback; fullscreenCallback = null;
        if (web != null) web.setVisibility(View.VISIBLE);
        toolbar.setVisibility(View.VISIBLE); status.setVisibility(View.VISIBLE); location.setVisibility(View.VISIBLE);
        if (callback != null) callback.onCustomViewHidden();
        content.requestFocus(); immersive();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    @Override protected void onPause() {
        foreground = false; main.removeCallbacks(videoPoll);
        if (web != null) {
            web.evaluateJavascript("if(window.__jianshiTv)window.__jianshiTv.pause();", null);
            web.onPause();
        }
        super.onPause();
    }
    @Override protected void onStop() {
        main.removeCallbacks(slowPage); main.removeCallbacks(videoPoll); closePopup(); pageGeneration++; pageReady = false;
        if (web != null) {
            String current = web.getUrl(); resumeUrl = WebChannelRoutes.validPageUrl(current) ? current : pageUrl;
            leaveFullscreen(); web.stopLoading(); web.loadUrl("about:blank");
        }
        super.onStop();
    }
    @Override protected void onResume() {
        super.onResume(); foreground = true; immersive();
        if (web != null) {
            web.onResume();
            if (resumeUrl != null) { String url = resumeUrl; resumeUrl = null; navigate(url); }
            else if (pageReady) { main.removeCallbacks(videoPoll); main.post(videoPoll); }
        }
    }
    @Override protected void onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null); closePopup(); leaveFullscreen();
        if (web != null) {
            web.stopLoading(); web.setWebChromeClient(null); web.setWebViewClient(new WebViewClient());
            if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
            web.destroy(); web = null;
        }
        super.onDestroy();
    }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) immersive(); }
}
