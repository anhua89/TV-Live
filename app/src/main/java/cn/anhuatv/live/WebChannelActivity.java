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
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import cn.anhuatv.live.core.WebChannelRoutes;

/** A separate, optional page for Chengdu TV; the native player is stopped by its owner. */
public final class WebChannelActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private FrameLayout content;
    private LinearLayout toolbar;
    private TextView status;
    private Pointer pointer;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private String pageUrl, resumeUrl;
    private boolean pageFailed, destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        String saved = getPreferences(MODE_PRIVATE).getString("page_url", WebChannelRoutes.DEFAULT_URL);
        pageUrl = WebChannelRoutes.validPageUrl(saved) ? saved : WebChannelRoutes.DEFAULT_URL;
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setBackgroundColor(Color.BLACK);
        toolbar = new LinearLayout(this); toolbar.setPadding(dp(10), dp(4), dp(10), dp(4)); toolbar.setBackgroundColor(0xFF12202E);
        addButton("频道列表", this::finish);
        addButton("刷新网页", this::loadPage);
        addButton("修改网址", this::editUrl);
        addButton("网页工具", this::tools);
        page.addView(toolbar);
        status = new TextView(this); status.setTextColor(Color.WHITE); status.setTextSize(14);
        status.setPadding(dp(14), dp(5), dp(14), dp(5)); status.setBackgroundColor(0xFF12202E); page.addView(status);
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
        if (web != null) loadPage();
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
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(true); settings.setBuiltInZoomControls(true); settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // No native Javascript interface, URL interception proxy, account, or stream extraction.
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !WebChannelRoutes.validPageUrl(url);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !WebChannelRoutes.validPageUrl(request.getUrl().toString());
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if ("about:blank".equals(url)) return;
                pageFailed = false; message("正在打开成都电视网页…  菜单键：网页工具；返回键：频道列表");
                main.removeCallbacks(slowPage); main.postDelayed(slowPage, 20000);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if ("about:blank".equals(url)) return;
                main.removeCallbacks(slowPage);
                if (!pageFailed) message("网页已加载，请在网页内选择对应频道。方向键移光标，确定点击；菜单键打开工具。");
            }
            @Override public void onReceivedError(WebView view, int code, String description, String url) {
                if (Build.VERSION.SDK_INT < 23) pageError("网页连接失败：" + description);
            }
            @TargetApi(23)
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) pageError("网页连接失败：" + error.getDescription());
            }
            @TargetApi(23)
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) pageError("网站返回 HTTP " + response.getStatusCode());
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel(); pageError("网页证书校验失败");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreen != null) { callback.onCustomViewHidden(); return; }
                fullscreen = view; fullscreenCallback = callback;
                web.setVisibility(View.GONE); toolbar.setVisibility(View.GONE); status.setVisibility(View.GONE);
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
        resumeUrl = null; leaveFullscreen(); web.stopLoading(); web.loadUrl(pageUrl); content.requestFocus();
    }
    private void pageError(String detail) {
        pageFailed = true; main.removeCallbacks(slowPage);
        message(detail + "。菜单键可重试、修改网址或用电视浏览器打开；返回键回到频道列表。");
    }
    private final Runnable slowPage = () -> message("网页加载较慢。菜单键可刷新、修改网址或使用电视浏览器；返回键回到频道列表。");
    private void message(String text) { if (!destroyed) status.setText(text); }

    private void tools() {
        String[] labels = {"刷新网页", "网页返回上一页", "放大网页", "缩小网页", "修改成都网页地址", "在电视浏览器打开", "返回频道列表"};
        new AlertDialog.Builder(this).setTitle("成都电视台 · 网页工具").setItems(labels, (d, i) -> {
            switch (i) {
                case 0: loadPage(); break;
                case 1: if (web != null && web.canGoBack()) web.goBack(); else toast("没有上一页"); break;
                case 2: if (web != null) web.zoomIn(); break;
                case 3: if (web != null) web.zoomOut(); break;
                case 4: editUrl(); break;
                case 5: openBrowser(); break;
                case 6: finish(); break;
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
        toolbar.setVisibility(View.VISIBLE); status.setVisibility(View.VISIBLE);
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
        if (web != null) {
            web.evaluateJavascript("(function(){var m=document.querySelectorAll('video,audio');for(var i=0;i<m.length;i++)m[i].pause();})()", null);
            web.onPause();
        }
        super.onPause();
    }
    @Override protected void onStop() {
        main.removeCallbacks(slowPage);
        if (web != null) {
            String current = web.getUrl(); resumeUrl = WebChannelRoutes.validPageUrl(current) ? current : pageUrl;
            leaveFullscreen(); web.stopLoading(); web.loadUrl("about:blank");
        }
        super.onStop();
    }
    @Override protected void onResume() {
        super.onResume(); immersive();
        if (web != null) {
            web.onResume();
            if (resumeUrl != null) { String url = resumeUrl; resumeUrl = null; web.loadUrl(url); content.requestFocus(); }
        }
    }
    @Override protected void onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null); leaveFullscreen();
        if (web != null) {
            web.stopLoading(); web.setWebChromeClient(null); web.setWebViewClient(new WebViewClient());
            if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
            web.destroy(); web = null;
        }
        super.onDestroy();
    }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) immersive(); }
}
