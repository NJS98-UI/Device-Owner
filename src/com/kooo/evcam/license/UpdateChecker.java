package com.kooo.evcam.license;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;

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
 * 说明由用户单独上传维护）。发现新版本 → 下载 APK → Device Owner 静默安装。
 * 每 30 分钟检查一次（license 轮询 3 秒那次只顺带缓存 update 字段，
 * 下载安装动作低频独立执行，避免每次轮询都拉 APK）。
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final long CHECK_MS = 30L * 60_000L;

    public interface Callback {
        /** 主线程回调：有新版本。version=新版本名，apk=已下载好的本地文件，notes=更新说明。 */
        void onNewVersion(String version, File apk, String notes);
        void onMessage(String msg);
    }

    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean running;

    private UpdateChecker() { }

    /** 启动周期检查（幂等）。 */
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
        main.post(task[0]);
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
            String notes = upd.optString("notes", "");
            File apk = download(app, url);
            if (apk == null) {
                if (manual) post(() -> cb.onMessage("下载失败，请检查网络"));
                return;
            }
            post(() -> cb.onNewVersion(serverV, apk, notes));
        } catch (Throwable t) {
            AppLog.w(TAG, "检查更新失败: " + t);
            if (manual) post(() -> cb.onMessage("检查更新失败: " + t.getMessage()));
        }
    }

    /** Device Owner 静默安装（无需用户确认）。 */
    public static void installSilently(Context app, File apk) {
        io.execute(() -> {
            try {
                PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sessionId = pi.createSession(params);
                PackageInstaller.Session session = pi.openSession(sessionId);
                try (InputStream in = new java.io.FileInputStream(apk);
                     java.io.OutputStream out = session.openWrite("app.apk", 0, apk.length())) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent statusIntent = new Intent("com.kooo.evcam.INSTALL_RESULT");
                PendingIntent status = PendingIntent.getBroadcast(app, 2002, statusIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                session.commit(status.getIntentSender());
                session.close();
                AppLog.d(TAG, "静默安装已提交");
            } catch (Throwable t) {
                AppLog.w(TAG, "静默安装失败: " + t);
            }
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
