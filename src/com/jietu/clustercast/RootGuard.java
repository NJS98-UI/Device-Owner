package com.jietu.clustercast;

import com.kooo.evcam.AppLog;

/**
 * Root 防休眠：持内核级休眠锁（/sys/power/wake_lock）。
 * 厂商整机休眠无视框架层 wakelock 也无视 setAlarmClock（2026-10-03 实车实锤，
 * 锁链 2 拍即停、跨休眠的 Camera2 会话把 HAL 卡死到只能重启），只有内核 PM
 * 层的 wake_lock 能从源头挡住 suspend 入口——写 /sys/power 需要 root。
 * 持锁期间整机不进休眠：Camera2 会话跨夜安全，息屏/休眠录像不间断。
 * 锁在内核里不随进程死掉消失，只有显式 wake_unlock 或重启才释放。
 */
public final class RootGuard {
    private static final String TAG = "RootGuard";
    public static final String LOCK_NAME = "clustercast";

    /** 回读验证过的持锁状态（只作 UI 展示；权威在 /sys 本身）。 */
    private static volatile boolean sHeld = false;

    public static boolean isHeld() { return sHeld; }

    public interface Result { void onDone(boolean ok); }

    /** 后台跑（su 调用可能弹授权框、会阻塞），完成后回调（回调在子线程）。 */
    public static void ensureAsync(final Result cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = acquire();
                if (cb != null) cb.onDone(ok);
            }
        }, "rootguard").start();
    }

    public static void releaseAsync(final Result cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = release();
                if (cb != null) cb.onDone(ok);
            }
        }, "rootguard").start();
    }

    /** 是否有可用 root：su 跑 id 返回 uid=0。 */
    public static boolean hasRoot() {
        String out = su("id");
        return out != null && out.contains("uid=0");
    }

    /** 写 wake_lock 并回读验证（写入必须回读，set 不报错不代表落住了）。 */
    private static synchronized boolean acquire() {
        try {
            su("echo " + LOCK_NAME + " > /sys/power/wake_lock");
            String cur = su("cat /sys/power/wake_lock");
            boolean ok = cur != null && cur.contains(LOCK_NAME);
            sHeld = ok;
            AppLog.d(TAG, ok ? "root 休眠锁已持上（回读确认）" : "wake_lock 写入后回读不到，未生效");
            return ok;
        } catch (Throwable t) {
            sHeld = false;
            AppLog.w(TAG, "root 休眠锁获取失败: " + t);
            return false;
        }
    }

    /** 释放内核锁（开关关闭/退出 app 时调）。同步版，杀进程前必须跑完用这个。 */
    public static synchronized boolean release() {
        try {
            su("echo " + LOCK_NAME + " > /sys/power/wake_unlock");
            String cur = su("cat /sys/power/wake_lock");
            boolean ok = cur == null || !cur.contains(LOCK_NAME);
            sHeld = !ok;
            AppLog.d(TAG, ok ? "root 休眠锁已释放（回读确认）" : "wake_unlock 后锁仍在");
            return ok;
        } catch (Throwable t) {
            AppLog.w(TAG, "root 休眠锁释放失败: " + t);
            return false;
        }
    }

    /** 起 su 交互会话跑一条命令，返回 stdout（失败/无 root 返回 null）。 */
    private static String su(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            java.io.OutputStream os = p.getOutputStream();
            os.write((cmd + "\n").getBytes("UTF-8"));
            os.write("exit\n".getBytes("UTF-8"));
            os.flush();
            String out = read(p.getInputStream());
            p.waitFor();
            return out;
        } catch (Throwable t) {
            AppLog.w(TAG, "su 执行失败: " + t);
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static String read(java.io.InputStream in) {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private RootGuard() { }
}
