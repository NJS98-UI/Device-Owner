package com.ahui.clustercast;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

/**
 * 车身控制：车窗 / 尾门 / 后视镜 / 档位。
 * 协议全部来自系统包取证（抓包数据/反编译/extra.dump 官方常量表 + 总线原始读写行）：
 *   162~165 四扇窗分立通道（327681，写入 1关/2开/3透气，实测有效）；
 *   175 = ID_CAR_COMBINE_WINDOW_SET 组合命令（照发，但不当主路径）；
 *   尾门【写】327681/92 = ID_CAR_REAR_DOOR_CONTROL（开=2 关=1）——这是控制指令；
 *   尾门【读】327684/162 = 尾门开度（TrunkInterpolation）：0=关、非0=开。
 *     取证：关着尾门时 92 恒读 [2]（log:108577 vs 3D_MODEL trunkState=false log:108090），
 *     而 327684/162=[0] 与原车 getTrunkDoorState open=false（log:108089）逐毫秒对齐 ——
 *     之前拿 92 的读数判开闭，所以才「关闭读成打开」。兜底再读 327684/19
 *     （ReadOnlyID.ID_TRUNK_STATE 官方常量）。
 *   后视镜【位置】327681/201 = ID_CAR_DRIVER_MIRROR_LOCATION（左）、
 *     327681/218 = ID_CAR_PASSENGER_MIRROR_LOCATION（右）：可读（log:108380/108385
 *     values=[1]）也可写。用户要的就是这套：手动把镜调好 → 读取 → 保存；
 *     挂 R 写「下翻位」、退 R 写回「正常位」。
 *     209/210 那套「方向值标定」全部删除 —— 实机证明它没用，还把人绕晕。
 *   327684/26 = 档位（1P 2R 3N 4D）。
 *
 * 关键要求（用户实机反馈的问题）：
 *   · 「一条一条执行」不行 —— 批量动作一次并发下发（左右镜同一段代码里连着写，
 *     中间不 sleep），照抄原车语音的节奏（win_round.log.gz 实测四条 1~2ms 发完）。
 *   · 总线 set 是 fire-and-forget，返回不代表 MCU 接受，所以每个动作都回读比对；
 *     读不到期望值就报「未确认」，绝不把「反射没抛异常」当成功。
 */
public class CarCtl {

    public static final int EV_BODY = 327681;      // 0x50001 MODULE_CAR_SETTING
    public static final int EV_STATE = 327684;     // 0x50004 只读车辆状态

    public static final int[] WIN_CMD = {162, 163, 164, 165};
    public static final String[] WIN_NAME = {"主驾", "副驾", "左后", "右后"};
    public static final int CMD_WIN_COMBO = 175;   // ID_CAR_COMBINE_WINDOW_SET

    public static final int WIN_CLOSE = 1;
    public static final int WIN_OPEN = 2;
    public static final int WIN_VENT = 3;

    public static final int CMD_TAILGATE = 92;     // ID_CAR_REAR_DOOR_CONTROL：只用于【写】
    public static final int TRUNK_ANGLE = 162;     // 327684：尾门开度（TrunkInterpolation）0=关 非0=开
    public static final int TRUNK_STATE = 19;      // 327684：ReadOnlyID.ID_TRUNK_STATE 官方常量，兜底

    public static final int MIRROR_POS_L = 201;    // ID_CAR_DRIVER_MIRROR_LOCATION（左镜位置，可读可写）
    public static final int MIRROR_POS_R = 218;    // ID_CAR_PASSENGER_MIRROR_LOCATION（右镜位置）

    public static final int CMD_GEAR = 26;         // 1=P 2=R 3=N 4=D

    private static final long WIN_TIMEOUT = 8000L;
    private static final long TAIL_TIMEOUT = 15000L;

    public static String winName(int v) {
        switch (v) {
            case WIN_CLOSE: return "关";
            case WIN_OPEN:  return "开";
            case WIN_VENT:  return "透气";
            case 0:         return "空闲";
            case 4:         return "运行中";
            default:        return v < 0 ? "未知" : "值" + v;
        }
    }

    public static String gearName(int g) {
        switch (g) {
            case 1: return "P";
            case 2: return "R";
            case 3: return "N";
            case 4: return "D";
            default: return "?";
        }
    }

    /** 档位总线编码（受控挂挡实测 + AOSP GEAR_SELECTION 一致）。 */
    public static String gearLetter(int g) {
        switch (g) {
            case 1: return "P";
            case 2: return "R";
            case 3: return "N";
            case 4: return "D";
            default: return null;
        }
    }

    // ---------- 实例部分 ----------

    private final Context ctx;
    private final Handler ui;
    private final LogCallback log;
    private final HandlerThread bg;
    private final Handler worker;

    public interface IntCallback {
        void accept(int value);
    }

    public interface IntArrayCallback {
        void accept(int[] value);
    }

    public interface MirrorCallback {
        void accept(int left, int right);
    }

    public interface ResultCallback {
        void accept(boolean ok, String message);
    }

    public interface LogCallback {
        void log(String message);
    }

    public CarCtl(Context c, Handler ui, LogCallback log) {
        this.ctx = c.getApplicationContext();
        this.ui = ui;
        this.log = log;
        this.bg = new HandlerThread("carctl");
        this.bg.start();
        this.worker = new Handler(bg.getLooper());
    }

    public void close() {
        try { bg.quitSafely(); } catch (Throwable t) { }
    }

    // ---------- 读取 ----------

    /** 档位：1=P 2=R 3=N 4=D，读不到 0。 */
    public void readGear(final IntCallback cb) {
        readOne(EV_STATE, CMD_GEAR, new IntCallback() {
            @Override
            public void accept(int value) {
                cb.accept(value);
            }
        });
    }

    /**
     * 尾门真实开度：327684/162（0=关、非0=开），读不到再兜底 327684/19
     * （ReadOnlyID.ID_TRUNK_STATE）。都不回执给 -1，界面照实写「未知」。
     * 92 是控制指令，它的读数恒为 [2]，拿它判开闭就是「关闭读成打开」的根因。
     */
    public void readTailgate(final IntCallback cb) {
        worker.post(new Runnable() {
            @Override
            public void run() {
                Vd vd = Vd.connect(ctx);
                int v = vd.ok() ? tailgateRaw(vd) : -1;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.accept(v);
                    }
                });
            }
        });
    }

    private int tailgateRaw(Vd vd) {
        int[] arr = vd.get(EV_STATE, TRUNK_ANGLE);
        if (arr != null && arr.length > 0) return arr[0];
        arr = vd.get(EV_STATE, TRUNK_STATE);
        if (arr != null && arr.length > 0) return arr[0];
        return -1;
    }

    /**
     * 左右后视镜当前位置（327681/201 左、327681/218 右）。
     * 总线读得到（log:108380/108385 values=[1]）；读不到回 -1，界面写「无回执」。
     */
    public void readMirrorPos(final MirrorCallback cb) {
        worker.post(new Runnable() {
            @Override
            public void run() {
                Vd vd = Vd.connect(ctx);
                int l = -1;
                int r = -1;
                if (vd.ok()) {
                    int[] arr = vd.get(EV_BODY, MIRROR_POS_L);
                    if (arr != null && arr.length > 0) l = arr[0];
                    arr = vd.get(EV_BODY, MIRROR_POS_R);
                    if (arr != null && arr.length > 0) r = arr[0];
                }
                final int fl = l, fr = r;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.accept(fl, fr);
                    }
                });
            }
        });
    }

    /**
     * 把一对位置值同时推给左右镜（同一段循环里连着写，中间不 sleep —— 等效「两边一起按」），
     * 写完统一回读比对。回读对不上就报「未确认」，绝不把「发出去了」当「镜位动了」。
     */
    public void writeMirrorPos(final int left, final int right, final ResultCallback cb) {
        worker.post(new Runnable() {
            @Override
            public void run() {
                Vd vd = Vd.connect(ctx);
                if (!vd.ok()) {
                    finish("后视镜写入失败：总线未连", false, cb);
                    return;
                }
                if (left < 0 || right < 0) {
                    finish("没存过这一对位置，写不了", false, cb);
                    return;
                }
                int s1 = vd.sendArr(EV_BODY, MIRROR_POS_L, new int[]{left});
                int s2 = vd.sendArr(EV_BODY, MIRROR_POS_R, new int[]{right});
                if (s1 == 0 && s2 == 0) {
                    finish("后视镜写入失败：201/218 两条通道都没发出去", false, cb);
                    return;
                }
                SystemClock.sleep(600);
                int bl = -1;
                int br = -1;
                int[] arr = vd.get(EV_BODY, MIRROR_POS_L);
                if (arr != null && arr.length > 0) bl = arr[0];
                arr = vd.get(EV_BODY, MIRROR_POS_R);
                if (arr != null && arr.length > 0) br = arr[0];
                boolean ok = bl == left && br == right;
                if (ok) {
                    finish("后视镜已推到 左=" + left + " 右=" + right + "（回读一致）", true, cb);
                } else {
                    finish("后视镜写入未确认：发 左=" + left + " 右=" + right +
                           "，回读 左=" + bl + " 右=" + br, false, cb);
                }
            }
        });
    }

    private void readOne(final int ev, final int cmd, final IntCallback cb) {
        worker.post(new Runnable() {
            @Override
            public void run() {
                Vd vd = Vd.connect(ctx);
                int v = 0;
                if (vd.ok()) {
                    int[] arr = vd.get(ev, cmd);
                    if (arr != null && arr.length > 0) v = arr[0];
                }
                final int fv = v;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.accept(fv);
                    }
                });
            }
        });
    }

    /** 四扇窗当前状态（162~165 各读一次），顺序同 WIN_CMD，读不到为 -1。 */
    public void readWindows(final IntArrayCallback cb) {
        worker.post(new Runnable() {
            @Override
            public void run() {
                Vd vd = Vd.connect(ctx);
                int[] out = new int[WIN_CMD.length];
                for (int i = 0; i < out.length; i++) out[i] = -1;
                if (vd.ok()) {
                    for (int i = 0; i < WIN_CMD.length; i++) {
                        int[] arr = vd.get(EV_BODY, WIN_CMD[i]);
                        if (arr != null && arr.length > 0) out[i] = arr[0];
                    }
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.accept(out);
                    }
                });
            }
        });
    }

    // ---------- 车窗：一条组合命令，不逐条 ----------

    /**
     * 四窗同时动。取证结论（win_round.log.gz 逐事件时间戳）：原车语音四窗就是
     * 同一毫秒内连发 162/163/164/165（间隔 0.5~2ms），而组合命令 175 在整机日志里
     * 只作为「注册项」出现过一次，从来没有触发记录 —— 所以不能拿它当主路径，
     * 否则第一下要白等 8 秒超时才回退。
     * 现在的顺序：先照抄语音节奏毫秒级连发四路（主路径），顺手再补一发 175
     * （认了更好，不认也不影响），最后统一回读一次判定。
     */
    public void setAllWindows(final int value, final ResultCallback cb) {
        final String name = "四窗→" + winName(value);
        worker.post(new Runnable() {
            @Override
            public void run() {
                Vd vd = Vd.connect(ctx);
                if (!vd.ok()) {
                    String err = vd.lastError != null ? vd.lastError : "未知";
                    finish(name + " 失败：总线未连（" + err + "）", false, cb);
                    return;
                }
                int sent = 0;
                // 中间绝不 sleep、绝不回读：四路连着发完才去看状态。
                for (int cmd : WIN_CMD) {
                    if (vd.sendArr(EV_BODY, cmd, new int[]{value}) > 0) sent++;
                }
                if (sent == 0) {
                    finish(name + " 失败：四条分立通道下发都没成功", false, cb);
                    return;
                }
                vd.sendArr(EV_BODY, CMD_WIN_COMBO, new int[]{value, value, value, value});
                boolean ok = waitWin(vd, value);
                if (ok) {
                    finish(name + " 已生效（" + sent + "/4 条并发下发，同原车语音节奏）", true, cb);
                } else {
                    finish(name + " 未确认（回读没到位，可能这台车不认这条指令）", false, cb);
                }
            }
        });
    }

    /** 到位判定：0/4 是电机运行中的瞬时态，等到出现 1/2/3 且与期望一致才算生效。 */
    private boolean waitWin(Vd vd, int want) {
        long end = SystemClock.elapsedRealtime() + WIN_TIMEOUT;
        while (SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(400);
            int hit = 0;
            for (int cmd : WIN_CMD) {
                int[] arr = vd.get(EV_BODY, cmd);
                if (arr != null && arr.length > 0 && arr[0] == want) hit++;
            }
            if (hit == WIN_CMD.length) return true;
        }
        return false;
    }

    // ---------- 尾门：一条命令 = 一次长按等效 ----------

    /**
     * 尾门：开=发 2、关=发 1（327681/92 ID_CAR_REAR_DOOR_CONTROL，这是控制指令，
     * 写成读状态是错的）。生效与否用 327684/162 的开度回读判：0=关、非0=开。
     */
    public void setTailgate(final boolean open, final ResultCallback cb) {
        worker.post(new Runnable() {
            @Override
            public void run() {
                Vd vd = Vd.connect(ctx);
                String label = "尾门→" + (open ? "开" : "关");
                if (!vd.ok()) {
                    finish(label + " 失败：总线未连", false, cb);
                    return;
                }
                if (vd.sendArr(EV_BODY, CMD_TAILGATE, new int[]{open ? 2 : 1}) == 0) {
                    finish(label + " 失败：两条通道下发都没成功", false, cb);
                    return;
                }
                long end = SystemClock.elapsedRealtime() + TAIL_TIMEOUT;
                String last = "开度没有回读";
                while (SystemClock.elapsedRealtime() < end) {
                    SystemClock.sleep(500);
                    int v = tailgateRaw(vd);
                    if (v < 0) {
                        last = "开度没有回读";
                        continue;
                    }
                    if ((v != 0) == open) {
                        finish(label + " 已生效（开度回读=" + v + "）", true, cb);
                        return;
                    }
                    last = "开度回读=" + v;
                }
                finish(label + " 未确认（" + last + "，电动尾门本来就要十几秒）", false, cb);
            }
        });
    }

    // ---------- 下发 + 回读 ----------

    private void finish(final String m, final boolean ok, final ResultCallback cb) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                log.log(m);
                cb.accept(ok, m);
            }
        });
    }
}
