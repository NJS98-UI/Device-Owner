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
        // 一机一码授权拦截：未激活/试用到期/断网停用一律不开录
        com.kooo.evcam.license.LicenseManager lm = com.kooo.evcam.license.LicenseManager.get();
        if (!lm.isAllowed()) {
            AppLog.w(TAG, "授权拦截：状态 " + lm.getState() + "，不开录");
            return;
        }
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
                android.graphics.SurfaceTexture rec = q.getInputTexture(i);
                if (rec == null) {
                    AppLog.w(TAG, "cam" + CAMS[i] + " 拿不到合成纹理");
                    continue;
                }
                // 界面侧已在流的实例直接收编（双输出，页面预览不断），别再开
                // 新实例去抢同一路——两套客户端互 evict 会死循环（实车实锤）
                Surround s = Surround.findLive(CAMS[i]);
                if (s != null) {
                    s.setRecordTexture(rec);
                    AppLog.d(TAG, "cam" + CAMS[i] + " 收编界面侧在流实例（双输出）");
                } else {
                    s = new Surround(app, CAMS[i]);
                    s.setRecordTexture(rec);
                    s.start(rec);   // recTex-only：没有预览面，纯录像
                }
                sCams[i] = s;
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
        sResumeGen++;   // 作废挂起的续录任务：唤醒后 2.5 秒内又熄屏时不得开录
        if (sComposer == null) return;
        // 「息屏录制」开着：前台服务持 PARTIAL_WAKE_LOCK 系统不进休眠（HAL 无跨休眠风险），
        // 不挂起，熄屏继续录新分段。关闭时走原路：停录收尾成文件，唤醒续录。
        if (ctx != null && new com.kooo.evcam.AppConfig(ctx).isScreenOffRecordingEnabled()) {
            AppLog.d(TAG, "息屏录制开着，熄屏继续录像（不挂起）");
            sSleepPaused = false;
            return;
        }
        sSleepPaused = true;
        stopInternal();
        AppLog.d(TAG, "熄屏暂停录像，相机已全部释放");
    }

    /** 唤醒：休眠前在录就自动续录（新分段）。SCREEN_ON 瞬间 HAL/ISP 还没上电，
     *  立刻开 4 路相机容易失败——延迟 2.5 秒再开，失败自动重试 3 次。 */
    public static synchronized void resumeFromSleep(Context ctx) {
        if (!sSleepPaused || sComposer != null) return;
        sResumeAttempts = 0;
        scheduleResume(ctx, RESUME_DELAY_MS);
        AppLog.d(TAG, "唤醒，" + (RESUME_DELAY_MS / 1000) + " 秒后续录（等 HAL 就绪）");
    }

    private static final long RESUME_DELAY_MS = 2500;
    private static final long RESUME_RETRY_MS = 5000;
    private static final int RESUME_MAX_RETRY = 3;
    private static final android.os.Handler sUi =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** 续录代次：scheduleResume 递增，suspendForSleep 取消旧任务靠它失效。 */
    private static volatile int sResumeGen = 0;
    private static int sResumeAttempts = 0;

    private static void scheduleResume(Context ctx, long delay) {
        final Context app = ctx.getApplicationContext();
        final int gen = ++sResumeGen;
        sUi.postDelayed(() -> {
            if (gen == sResumeGen) finishResume(app, gen);
        }, delay);
    }

    private static void finishResume(Context ctx, int gen) {
        synchronized (QuadAutoRecord.class) {
            if (!sSleepPaused || sComposer != null) return;
        }
        AppLog.d(TAG, "唤醒续录尝试 " + (sResumeAttempts + 1));
        start(ctx);
        if (sComposer != null) {
            sSleepPaused = false;
            sResumeAttempts = 0;
            AppLog.d(TAG, "唤醒续录成功");
        } else if (sResumeAttempts < RESUME_MAX_RETRY) {
            sResumeAttempts++;
            AppLog.w(TAG, "续录未就绪，" + (RESUME_RETRY_MS / 1000) + " 秒后重试");
            scheduleResume(ctx, RESUME_RETRY_MS);
        } else {
            // sSleepPaused 保持 true：下次 SCREEN_ON 会再走一遍续录
            sResumeAttempts = 0;
            AppLog.w(TAG, "续录多次失败，等下次亮屏再试");
        }
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
