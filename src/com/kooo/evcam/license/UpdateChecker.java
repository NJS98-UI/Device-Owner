package com.kooo.evcam.license;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.kooo.evcam.AppLog;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自更新：全部信息取自激活服务器 ux.json 第二排的 update 字段
 * （{"v":"1.1","u":"http://.../xxx.apk","notes":"更新说明..."}，地址与
 * 说明由用户单独上传维护）。发现新版本 → 下载 APK → 调用系统安装器安装。
 * 启动时立即检测一次，之后每 3 秒静默检测（读取 license 轮询缓存的
 * update 字段，无额外网络请求）；只在版本号大于本地且未安装过时才下载。
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final long CHECK_MS = 3_000L;

    public interface Callback {
        /** 主线程回调：有新版本。version=新版本名，url=下载地址，notes=更新说明。 */
        void onNewVersion(String version, String url, String notes);
        void onMessage(String msg);
    }

    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean running;

    private UpdateChecker() { }

    /** 启动周期检查（幂等）。启动时立即检测一次，之后每 3 秒静默检测。 */
    public static synchronized void start(Context ctx, Callback cb) {
        if (running) return;
        running = true;
        final Context app = ctx.getApplicationContext();
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            if (!running) return;
            io.execute(() -> checkOnce(app, cb, false));
            main.postDelayed(task[0], CHECK_MS);
        };
        // 立即检测一次
        io.execute(() -> checkOnce(app, cb, false));
        main.postDelayed(task[0], CHECK_MS);
    }

    /** 手动立即检查（设置页"检查更新"）。 */
    public static void checkNow(Context ctx, Callback cb) {
        final Context app = ctx.getApplicationContext();
        io.execute(() -> checkOnce(app, cb, true));
    }

    private static void checkOnce(Context app, Callback cb, boolean manual) {
        try {
            JSONObject upd = LicenseManager.lastUpdateInfo();
            if (upd == null) {
                if (manual) post(() -> cb.onMessage("未取到更新信息，稍后重试"));
                return;
            }
            String serverV = upd.optString("v", "");
            String url = upd.optString("u", "");
            if (serverV.length() == 0 || url.length() == 0) {
                if (manual) post(() -> cb.onMessage("服务器未配置更新"));
                return;
            }
            String localV = localVersion(app);
            if (compare(serverV, localV) <= 0) {
                if (manual) post(() -> cb.onMessage("已是最新版本 " + localV));
                return;
            }
            // 防重复安装：同一版本+地址只装一次（否则安装重启 app 后
            // 又发现"新版本"，无限重启循环）
            String key = serverV + "|" + url;
            android.content.SharedPreferences sp = app.getSharedPreferences("update", Context.MODE_PRIVATE);
            if (key.equals(sp.getString("lastInstalled", ""))) {
                AppLog.d(TAG, "版本 " + serverV + " 已安装过，跳过");
                return;
            }
            // 防止 3 秒周期检测反复弹窗：同一版本只通知一次（手动检查除外）
            if (!manual && key.equals(sp.getString("lastNotified", ""))) {
                return;
            }
            sp.edit().putString("lastNotified", key).apply();
            String notes = upd.optString("notes", "");
            post(() -> cb.onNewVersion(serverV, url, notes));
        } catch (Throwable t) {
            AppLog.w(TAG, "检查更新失败: " + t);
            if (manual) post(() -> cb.onMessage("检查更新失败: " + t.getMessage()));
        }
    }

    /** 调用系统安装器安装 APK（无需 Device Owner，弹系统安装确认界面）。 */
    public static void installWithSystemInstaller(Context app, File apk) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    app, "com.jietu.clustercast.fileprovider", apk);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            try {
                intent.setComponent(new android.content.ComponentName(
                        "com.android.packageinstaller", "com.android.packageinstaller.InstallStart"));
                app.startActivity(intent);
            } catch (Exception ex) {
                intent.setComponent(null);
                app.startActivity(intent);
            }
            AppLog.d(TAG, "系统安装器已启动");
        } catch (Throwable t) {
            AppLog.w(TAG, "安装失败: " + t);
            Toast.makeText(app, "安装失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 下载 APK 并调用系统安装器安装（后台下载，主线程回调进度）。 */
    public static void downloadAndInstall(Context app, String url, String version, Callback cb) {
        io.execute(() -> {
            File apk = download(app, url);
            if (apk == null) {
                post(() -> cb.onMessage("下载失败，请检查网络"));
                return;
            }
            // 标记已安装，防止重启后同版本再次触发弹窗
            String key = version + "|" + url;
            app.getSharedPreferences("update", Context.MODE_PRIVATE)
                    .edit().putString("lastInstalled", key).apply();
            post(() -> {
                installWithSystemInstaller(app, apk);
            });
        });
    }

    private static String localVersion(Context app) {
        try {
            return app.getPackageManager()
                    .getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0";
        }
    }

    /** 版本比较：按数字段逐段比（1.2.10 > 1.2.9）。 */
    private static int compare(String a, String b) {
        String[] as = a.split("\\.");
        String[] bs = b.split("\\.");
        int len = Math.max(as.length, bs.length);
        for (int i = 0; i < len; i++) {
            int x = i < as.length ? parseIntSafe(as[i]) : 0;
            int y = i < bs.length ? parseIntSafe(bs[i]) : 0;
            if (x != y) return x > y ? 1 : -1;
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return 0; }
    }

    private static File download(Context app, String url) {
        File dst = new File(app.getExternalFilesDir(null), "update.apk");
        File tmp = new File(dst.getAbsolutePath() + ".tmp");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            try {
                if (c.getResponseCode() != 200) return null;
                try (InputStream in = c.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (!tmp.renameTo(dst)) {
                    java.nio.file.Files.move(tmp.toPath(), dst.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return dst;
            } finally {
                c.disconnect();
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "APK 下载失败: " + t);
            return null;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private static void post(Runnable r) { main.post(r); }
}
