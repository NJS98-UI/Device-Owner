package com.jietu.clustercast;

import android.content.Context;
import android.os.PowerManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;

/**
 * 哨兵模式（停车守卫）：锁车熄屏后自动值守，任一车门被打开时自动录像。
 *
 * 触发源与 DoorGreeting 同源（2026-09-29 实车标定）：模块 327684，
 * cmdId 15/16/17/18/162 = 主驾/副驾/左后/右后/尾门，1=开 0=关。
 *
 * 设计要点：
 * - 持 PARTIAL_WAKE_LOCK 托住 CPU（照 DoorGreeting 已实车验证的模式）：
 *   系统不进 suspend，既保证"锁车睡觉"期间收得到门信号，也从根上规避
 *   跨休眠持相机会话卡死 HAL/ISP 的问题（2026-10-02 实车黑屏根因）。
 *   代价是锁车期间 CPU 不深睡（车机常供电，待机功耗略增，远低于息屏全程录像）。
 * - 窗口录制复用 QuadAutoRecord（幂等、无界面、停时全释放相机）。
 *   开录前必须先 Surround.resumeAll() 开闸——熄屏时 CastService 把闸门
 *   关了（sGateClosed），新开的流会被挂起；录完若仍熄屏重新关闸。
 * - 窗口期内再开门自动续期（人在车边装卸物品不中断）。
 * - 亮屏即取消窗口计时（不停录，会话交还正常录像逻辑）。
 */
public final class SentinelController {
    private static final String TAG = "SentinelController";
    private static final int EV_DOOR = 327684;
    private static final String[] DOOR_NAMES = {"主驾", "副驾", "左后", "右后", "尾门"};
    private static final int[] DOOR_CMD = {15, 16, 17, 18, 162};
    /** 录像窗口：触发一次录多久。 */
    private static final long WINDOW_MS = 60_000L;
    /** 门信号轮询周期，与 DoorGreeting 一致。 */
    private static final long POLL_MS = 150L;

    private static volatile boolean sRun;
    private static Thread sThread;
    private static Context sApp;
    private static PowerManager.WakeLock sWl;

    /** 熄屏中（CastService SCREEN_OFF/ON 通知）。 */
    private static volatile boolean sScreenDark;
    /** 窗口录制中。 */
    private static volatile boolean sWindowActive;
    private static volatile long sWindowDeadline;

    private SentinelController() { }

    /**
     * 幂等自适应入口：哨兵开关开→确保监听在跑；关→停（含收尾停录）。
     * CastService 启动和设置页切换都调这个。
     */
    public static synchronized void refresh(Context ctx) {
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        boolean want = new AppConfig(app).isSentinelModeEnabled();
        if (want && sThread == null) {
            startInternal(app);
        } else if (!want && sThread != null) {
            stopInternal();
        }
    }

    /** CastService SCREEN_OFF 通知。 */
    public static void setScreenDark(boolean dark) {
        sScreenDark = dark;
        if (!dark && sWindowActive) {
            // 亮屏：取消窗口计时但不停录，会话交还正常录像逻辑
            sWindowActive = false;
            AppLog.d(TAG, "亮屏，取消哨兵窗口计时（录制会话交还正常逻辑）");
        }
        if (dark) {
            ensureDozeWhitelist(sApp);
        }
    }

    private static void startInternal(Context app) {
        sApp = app;
        sRun = true;
        // 服务可能在熄屏态被拉起（熄火后台）：按真实屏幕状态初始化，
        // 否则要等下一次 SCREEN_OFF 广播才进入值守，漏掉中间的开门触发
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            sScreenDark = !pm.isInteractive();
        } catch (Throwable t) {
            sScreenDark = false;
        }
        holdCpu(app);
        ensureDozeWhitelist(app);
        sThread = new Thread(new Runnable() {
            @Override public void run() { loop(sApp); }
        }, "sentinel");
        sThread.start();
        AppLog.d(TAG, "哨兵模式已启动（锁车值守）");
    }

    /** 真正退出 app（MainActivity.exitApp）时调用：停线程、放锁、收尾停录。 */
    public static synchronized void shutdown() {
        if (sThread != null) stopInternal();
    }

    private static void stopInternal() {
        sRun = false;
        Thread t = sThread;
        sThread = null;
        if (t != null) t.interrupt();
        endWindow(true);
        dropCpu();
        AppLog.d(TAG, "哨兵模式已停止");
    }

    private static void loop(Context app) {
        AppLog.d(TAG, "哨兵轮询线程启动（150ms，327684 门信号）");
        Vd v = Vd.connect(app);
        int[] prev = new int[DOOR_NAMES.length];
        java.util.Arrays.fill(prev, -1);
        int failStreak = 0;
        while (sRun) {
            try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
            if (sWindowActive && System.currentTimeMillis() >= sWindowDeadline) {
                AppLog.d(TAG, "哨兵窗口到期，收尾停录");
                endWindow(false);
            }
            if (!v.ok()) { v = Vd.connect(app); continue; }
            try {
                for (int door = 0; door < DOOR_NAMES.length; door++) {
                    int now = v.getItem(EV_DOOR, DOOR_CMD[door]);
                    if (now < 0) continue;
                    int was = prev[door];
                    prev[door] = now;
                    if (was < 0 || was == now) continue; // 首读只记基准
                    AppLog.d(TAG, "车门 " + DOOR_NAMES[door] + " 327684/" + DOOR_CMD[door]
                            + " 状态 " + was + "→" + now);
                    if (was == 0 && now == 1) onDoorOpen();
                }
                failStreak = 0;
            } catch (Throwable t) {
                if (++failStreak >= 100) {
                    AppLog.w(TAG, "门信号连续读取失败，重连总线");
                    v = Vd.connect(app);
                    failStreak = 0;
                }
            }
        }
        AppLog.d(TAG, "哨兵轮询线程退出");
    }

    /** 轮询线程上：任一车门 0→1 边沿。 */
    private static void onDoorOpen() {
        if (!sScreenDark) return; // 亮屏时由正常录像逻辑管，哨兵不掺和
        if (QuadAutoRecord.isActive()) {
            // 已在录（息屏全程录制/开机自动录像恢复）：只需续期窗口
            if (sWindowActive) {
                sWindowDeadline = System.currentTimeMillis() + WINDOW_MS;
                AppLog.d(TAG, "哨兵窗口续期至 " + WINDOW_MS / 1000 + " 秒后");
            }
            return;
        }
        startWindow();
    }

    /** 开一个录像窗口：开闸 → 开录 → 计时。 */
    private static void startWindow() {
        AppLog.d(TAG, "哨兵触发：开录 " + WINDOW_MS / 1000 + " 秒窗口");
        // 熄屏闸门关着会挂起新流：先开闸再开录（息屏全程录制场景闸门本来就开，幂等）
        Surround.resumeAll();
        QuadAutoRecord.start(sApp);
        if (QuadAutoRecord.isActive()) {
            sWindowActive = true;
            sWindowDeadline = System.currentTimeMillis() + WINDOW_MS;
        } else {
            AppLog.w(TAG, "哨兵开录失败（QuadAutoRecord 未起来），保持值守");
        }
    }

    /**
     * 结束窗口：停录并恢复值守状态。
     * 只有哨兵自己开的窗口（sWindowActive）才停录，别误停其他来源的录制
     * （如亮屏手动录像、息屏全程录制）。
     * @param force true=用户关哨兵/退出 app；false=窗口到期（仅熄屏态停录）
     */
    private static void endWindow(boolean force) {
        if (!sWindowActive) return;
        sWindowActive = false;
        if (!QuadAutoRecord.isActive()) return;
        if (force || sScreenDark) {
            QuadAutoRecord.stop(sApp);
            if (sScreenDark) {
                // 若仍熄屏：重新关闸，恢复锁车值守的闸门关闭状态
                Surround.suspendAll();
            }
        }
    }

    private static void holdCpu(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            sWl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jietu:sentinel");
            sWl.setReferenceCounted(false);
            sWl.acquire();
        } catch (Throwable t) {
            AppLog.w(TAG, "WakeLock 获取失败: " + t);
        }
    }

    private static void dropCpu() {
        if (sWl != null) {
            try { sWl.release(); } catch (Throwable ignored) { }
            sWl = null;
        }
    }

    /** 状态摘要（设置页/主界面显示用）。 */
    public static boolean isRunning() { return sThread != null; }
    public static boolean isWindowActive() { return sWindowActive; }
}
