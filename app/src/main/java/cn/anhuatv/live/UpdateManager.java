package cn.anhuatv.live;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Public GitHub Release manifest, checksum, package/version/signature checks, then system installer. */
@SuppressWarnings("deprecation")
public final class UpdateManager {
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> task;
    private ProgressDialog dialog;
    private volatile boolean closed;
    private volatile int operation;
    private File pending;
    private boolean awaitingPermission;

    public UpdateManager(Activity activity) { this.activity = activity; }
    private boolean alive() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }
    private void ui(int generation, Runnable action) { main.post(() -> { if (alive() && generation == operation) action.run(); }); }

    public void check() {
        if (task != null && !task.isDone()) { toast("更新任务正在进行"); return; }
        String repo = BuildConfig.UPDATE_REPOSITORY;
        if (repo.isEmpty()) { show("尚未绑定更新仓库", "请使用 GitHub 工作流构建正式版；构建时会自动写入你的新仓库地址。"); return; }
        final int generation = ++operation;
        progress("检查更新", "读取 " + repo + " 的最新正式发布…");
        task = worker.submit(() -> {
            try {
                String url = "https://github.com/" + repo + "/releases/latest/download/update.json";
                JSONObject j = new JSONObject(Net.text(url, Net.noHeaders(), 64 * 1024, true));
                int code = j.getInt("versionCode");
                String name = j.getString("versionName");
                String apk = j.getString("apkUrl"); String sha = j.getString("sha256").toLowerCase(Locale.ROOT);
                long size = j.getLong("size");
                if (!j.getString("applicationId").equals(activity.getPackageName())) throw new IOException("更新属于其他应用");
                if (!apk.startsWith("https://github.com/" + repo + "/releases/download/")) throw new IOException("安装包地址与当前仓库不匹配");
                if (!sha.matches("[0-9a-f]{64}") || size <= 0 || size > 300L * 1024 * 1024) throw new IOException("更新描述不完整");
                if (j.optInt("minSdk", 21) > Build.VERSION.SDK_INT) throw new IOException("此更新不支持当前电视系统");
                ui(generation, () -> {
                    dismiss();
                    if (code <= BuildConfig.VERSION_CODE) { show("当前已是最新版本", "已安装 " + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）"); return; }
                    new AlertDialog.Builder(activity).setTitle("发现新版本 " + name)
                            .setMessage(j.optString("notes", "性能与体验更新") + "\n\n大小约 " + (size / 1024 / 1024) + " MB\n下载后仍需在电视上确认安装。")
                            .setPositiveButton("下载更新", (d, w) -> download(apk, sha, size, code))
                            .setNegativeButton("稍后", null).show();
                });
            } catch (Exception e) { ui(generation, () -> { dismiss(); show("无法检查更新", e.getMessage() + "\n首次发布前没有更新文件；也请检查电视能否访问 GitHub。"); }); }
        });
    }

    private void download(String url, String expectedSha, long expectedSize, int expectedCode) {
        final int generation = ++operation;
        progress("下载更新", "正在连接…");
        task = worker.submit(() -> {
            File directory = new File(activity.getCacheDir(), "updates");
            File partial = new File(directory, "update.part.apk"); File target = new File(directory, "update.apk");
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建更新目录");
                MessageDigest digest = MessageDigest.getInstance("SHA-256"); long total = 0; long lastUi = 0;
                try (Net.Response r = Net.open(url, Net.noHeaders(), 10000, true);
                     FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] block = new byte[65536]; int n;
                    while ((n = r.body.read(block)) != -1) {
                        if (Thread.currentThread().isInterrupted() || closed) throw new IOException("下载已取消");
                        total += n; if (total > expectedSize) throw new IOException("下载内容大于描述大小");
                        out.write(block, 0, n); digest.update(block, 0, n);
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastUi > 300) {
                            lastUi = now; int percent = (int)(total * 100 / expectedSize);
                            ui(generation, () -> { if (dialog != null) dialog.setMessage("已下载 " + percent + "%"); });
                        }
                    }
                    out.getFD().sync();
                }
                if (total != expectedSize || !hex(digest.digest()).equals(expectedSha)) throw new IOException("安装包大小或 SHA-256 校验失败");
                validate(partial, expectedCode);
                if (target.exists() && !target.delete()) throw new IOException("旧更新文件无法移除");
                if (!partial.renameTo(target)) throw new IOException("无法保存安装包");
                ui(generation, () -> {
                    dismiss(); pending = target;
                    new AlertDialog.Builder(activity).setTitle("更新已下载并通过校验")
                            .setMessage("应用标识、版本和签名均已核验。安装期间应用会暂时退出。")
                            .setPositiveButton("继续安装", (d, w) -> install())
                            .setNegativeButton("稍后", (d, w) -> { pending = null; }).show();
                });
            } catch (Exception e) {
                partial.delete(); ui(generation, () -> { dismiss(); show("更新下载失败", String.valueOf(e.getMessage())); });
            }
        });
    }

    private void validate(File file, int expectedCode) throws Exception {
        PackageManager pm = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = pm.getPackageInfo(activity.getPackageName(), flags);
        PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (archive == null || !activity.getPackageName().equals(archive.packageName)) throw new IOException("安装包应用标识不符");
        long version = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
        if (version != expectedCode || version <= BuildConfig.VERSION_CODE) throw new IOException("版本号不符合更新要求");
        Signature[] oldSign = signatures(installed), newSign = signatures(archive);
        if (oldSign.length == 0 || !signatureSet(oldSign).equals(signatureSet(newSign))) throw new IOException("签名与已安装应用不符，请检查构建时使用的密钥");
    }
    private Signature[] signatures(PackageInfo p) {
        if (Build.VERSION.SDK_INT >= 28 && p.signingInfo != null) return p.signingInfo.getApkContentsSigners();
        return p.signatures == null ? new Signature[0] : p.signatures;
    }
    private Set<String> signatureSet(Signature[] signatures) {
        Set<String> out = new HashSet<>();
        for (Signature s : signatures) out.add(Arrays.toString(s.toByteArray()));
        return out;
    }
    private void install() {
        if (pending == null || !pending.isFile()) { show("安装包不可用", "请重新检查更新。"); return; }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            awaitingPermission = true;
            try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))); }
            catch (Exception e) { awaitingPermission = false; show("请在电视设置中允许安装", "在安全或应用设置中，允许安华电视安装未知来源应用，然后再次检查更新。"); }
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", pending);
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent); pending = null;
        } catch (Exception e) { show("无法打开系统安装界面", String.valueOf(e.getMessage())); }
    }
    public void onResume() {
        if (awaitingPermission) {
            awaitingPermission = false;
            if (Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls()) install();
            else show("尚未允许安装", "请先在电视设置中允许安华电视安装未知来源应用。");
        }
    }
    private void progress(String title, String message) {
        dismiss(); dialog = new ProgressDialog(activity); dialog.setTitle(title); dialog.setMessage(message);
        dialog.setCancelable(true); dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> { operation++; if (task != null) task.cancel(true); }); dialog.show();
    }
    private void dismiss() { if (dialog != null) { dialog.dismiss(); dialog = null; } }
    private void show(String title, String message) { new AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("确定", null).show(); }
    private void toast(String s) { Toast.makeText(activity, s, Toast.LENGTH_SHORT).show(); }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString();
    }
    public void close() { closed = true; operation++; if (task != null) task.cancel(true); worker.shutdownNow(); dismiss(); main.removeCallbacksAndMessages(null); }
}
