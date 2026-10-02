package com.jietu.clustercast;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;

import java.io.File;

/**
 * 服务侧四合一自动录像单例：不依赖 Activity。开机后前台服务直接拉起
 * （4 路 Surround recTex-only + QuadComposer 分段合成），Activity 拉不起来
 * （Android 10+ 后台限制）也照录 —— 开机自动录像/后台录像/停车监控
 * （车机常供电，连续录即停车监控）都走这里。
 * 界面侧通过 cam()/owns() 复用同一批相机和合成器（双输出挂预览），避免
 * 两个组件抢同一路 Camera2；Activity 销毁时把会话移交（adopt）回这里。
 */
public final class QuadAutoRecord {
    private static final String TAG = "QuadAutoRecord";
    /** 与 MainActivity.QUAD_CAMS 一致：0=前 1=后 2=左 3=右。 */
    static final int[] CAMS = {4, 7, 6, 5};

    private static com.kooo.evcam.camera.QuadComposer sComposer;
    private static final Surround[] sCams = new Surround[4];

    public static synchronized boolean isActive() { return sComposer != null; }

    public static synchronized com.kooo.evcam.camera.QuadComposer composer() { return sComposer; }

    /** 自动录像占用中的那路相机（没在录或 id 对不上返回 null）。 */
    public static synchronized Surround cam(int camId) {
        if (sComposer == null) return null;
        for (Surround s : sCams) {
            if (s != null && s.camId() == camId) return s;
        }
        return null;
    }

    public static synchronized boolean owns(Surround s) {
        if (sComposer == null || s == null) return false;
        for (Surround c : sCams) if (c == s) return true;
        return false;
    }

    /** 开录。幂等；单路相机打不开由 Surround 自己重连，合成器起不来才整体回退。 */
    public static synchronized void start(Context ctx) {
        if (sComposer != null) return;
        final Context app = ctx.getApplicationContext();
        try {
            com.kooo.evcam.CameraForegroundService.start(app, "行车记录仪", "自动录像中");
            File dir = StorageHelper.getVideoDir(app);
            String name = "quad_" + com.kooo.evcam.camera.QuadComposer.newSegmentName(dir);
            com.kooo.evcam.camera.QuadComposer q = new com.kooo.evcam.camera.QuadComposer();
            q.setSegmentDurationMs(new AppConfig(app).getSegmentDurationMs());
            q.setCallback(new com.kooo.evcam.camera.QuadComposer.Callback() {
                @Override public void onStarted(String path) { AppLog.d(TAG, "分段开始 " + path); }
                @Override public void onStopped(String path) { AppLog.d(TAG, "分段收尾 " + path); }
                @Override public void onError(String err) { AppLog.w(TAG, "合成器错误 " + err); }
            });
            q.start(app, dir.getAbsolutePath(), name, 1920, 1080, 8000000, 30);
            sComposer = q;
            AppLog.d(TAG, "自动录像启动（服务侧，无界面）");
            for (int i = 0; i < CAMS.length; i++) {
                Surround s = new Surround(app, CAMS[i]);
                sCams[i] = s;
                android.graphics.SurfaceTexture rec = q.getInputTexture(i);
                if (rec == null) {
                    AppLog.w(TAG, "cam" + CAMS[i] + " 拿不到合成纹理");
                    continue;
                }
                s.setRecordTexture(rec);
                s.start(rec);   // recTex-only：没有预览面，纯录像
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "自动录像启动失败: " + t);
            stopInternal();
        }
    }

    /** Activity 销毁时把自己正在录的会话移交过来，后台接着录。 */
    public static synchronized void adopt(com.kooo.evcam.camera.QuadComposer q, Surround[] cams) {
        if (q == null || cams == null || sComposer != null) return;
        sComposer = q;
        for (int i = 0; i < CAMS.length && i < cams.length; i++) sCams[i] = cams[i];
        AppLog.d(TAG, "Activity 会话已移交，后台继续录");
    }

    /** 停录并释放四路相机（用户在界面上明确停止时）。 */
    public static synchronized void stop(Context ctx) {
        if (sComposer == null) return;
        stopInternal();
        AppLog.d(TAG, "自动录像已停止");
    }

    /** 休眠前在自动录像（唤醒要续录）。 */
    private static boolean sSleepPaused = false;

    /**
     * 熄屏休眠：停录并释放全部相机。
     * 根因（2026-10-02 实车）：跨休眠持有 Camera2 会话会把 HAL/ISP 卡死——
     * 唤醒后四路黑屏，连原车倒车影像都黑，只能重启。熄屏必须全释放。
     * 用户已确认熄屏停录；当前分段正常收尾成文件，唤醒后新分段续录。
     */
    public static synchronized void suspendForSleep(Context ctx) {
        if (sComposer == null) return;
        sSleepPaused = true;
        stopInternal();
        AppLog.d(TAG, "熄屏暂停录像，相机已全部释放");
    }

    /** 唤醒：休眠前在录就自动续录（新分段）。 */
    public static synchronized void resumeFromSleep(Context ctx) {
        if (!sSleepPaused || sComposer != null) return;
        sSleepPaused = false;
        AppLog.d(TAG, "唤醒续录");
        start(ctx);
    }

    private static void stopInternal() {
        com.kooo.evcam.camera.QuadComposer q = sComposer;
        sComposer = null;
        Surround[] cams = new Surround[sCams.length];
        for (int i = 0; i < sCams.length; i++) { cams[i] = sCams[i]; sCams[i] = null; }
        for (Surround s : cams) {
            try { if (s != null) s.stop(); } catch (Throwable ignored) { }
        }
        if (q != null) {
            try { q.stop(); } catch (Throwable ignored) { }
        }
    }

    private QuadAutoRecord() { }
}
