package com.jietu.clustercast;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import com.kooo.evcam.AppLog;

import java.io.File;

/**
 * 开门迎宾语 + 挡位语音：监听 5 个车门状态和挡位，cmdId 可配置。
 * 车门（2026-09-29 实车扫描标定，与原车 SVSetting SpiModel 同源）：模块 327684，
 * cmdId 15/16/17/18/162（主驾/副驾/左后/右后/尾门），1=开 0=关。
 * 每扇门的开/关各对应一个事件，播弹窗里配好的音频（GreetPlayer，完整参数），
 * 没配则播对应车门的内置原车语音；挂 D/R/P 播内置挡位语音。
 * 事件号 = 门*2 + (开?1:0)：0 主驾关 1 主驾开 … 8 尾门关 9 尾门开。
 */
public class DoorGreeting {
    private static final String TAG = "DoorGreeting";
    public static final String[] DOOR_NAMES = {"主驾", "副驾", "左后", "右后", "尾门"};
    private static final int EV_DOOR = 327684;
    private static final int EV_BODY = 327681;
    /** 挡位（327684/26）：1=P 2=R 3=N 4=D。 */
    private static final int CMD_GEAR = 26;
    /** 发动机运行状态：1=启动运行 0=熄火（2026-09-30 实车 EngineScan 两次启停循环标定；
     *  /74 为跟随的转速有效位，/14 是 0/1/2 电源模式，都没用它们）。 */
    private static final int CMD_ENGINE = 38;
    /** 尾门锁闩（327681/92，2026-09-29 实车标定读数：2=锁闭 0=非锁闭）。
     *  按开关/拉手瞬间门先解锁（→0），此时门还没动 —— 比 162 翻变早，按这个播。 */
    private static final int CMD_TAIL_LATCH = 92;

    private static volatile boolean sRun;
    private static Thread sThread;
    private static final Object LOCK = new Object();
    private static TextToSpeech sTts;
    private static volatile boolean sTtsReady;
    private static android.os.PowerManager.WakeLock sWl;
    // 播放并发：GreetPlayer 的 stop/play 全在专用线程跑（binder 调用可能被
    // 熄火后的假死 AudioService 卡住）。每个播报用一条新的 daemon 线程 ——
    // 单次卡死只废掉那一个播放线程，后面的开门/挡位/发动机照播不误；
    // 绝不共用一条线程串行排队（那会一卡全卡）。缓存池会自动回收空闲线程。
    private static final java.util.concurrent.ExecutorService sPlayer =
            java.util.concurrent.Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "greet-play");
                    t.setDaemon(true);
                    return t;
                }
            });

    private static void submitPlay(Runnable r) {
        try { sPlayer.execute(r); } catch (Throwable t) { AppLog.w(TAG, "播放任务提交失败: " + t); }
    }

    public static void start(Context ctx) {
        Cfg cfg = new Cfg(ctx);
        if (!cfg.greeting() || sThread != null) return;
        sRun = true;
        final Context app = ctx.getApplicationContext();
        initTts(app);
        // 休眠播报：车机休眠=CPU suspend，轮询线程跟着睡死就再也不播了。
        // 持 PARTIAL_WAKE_LOCK 托住 CPU，熄屏/休眠状态车门挡位照样播（车机常供电，不计电耗）。
        holdCpu(app);
        // Doze 深休眠（长睡后进入）会废掉非白名单应用的 wakelock——短睡能播、
        // 长睡哑的根因。没豁免就弹一次系统确认框。
        ensureDozeWhitelist(app);
        sThread = new Thread(new Runnable() { @Override public void run() { loop(app); }},
                "door-greeting");
        sThread.start();
        AppLog.d(TAG, "开门迎宾语监听已启动");
    }

    public static void stop() {
        sRun = false;
        Thread t = sThread;
        sThread = null;
        if (t != null) t.interrupt();
        dropCpu();
        synchronized (LOCK) {
            GreetPlayer.stop();
            if (sTts != null) {
                try { sTts.stop(); sTts.shutdown(); } catch (Throwable ignored) { }
                sTts = null;
            }
            sTtsReady = false;
        }
        AppLog.d(TAG, "开门迎宾语监听已停止");
    }

    /** 设置页试播：不依赖监听线程，单独初始化 TTS */
    public static void preview(Context ctx, int ev) {
        final Context app = ctx.getApplicationContext();
        synchronized (LOCK) {
            if (sTts == null) initTts(app);
        }
        play(app, new Cfg(app), ev);
    }

    /** 设置页试播一条挡位。 */
    public static void previewGear(Context ctx, int gear) {
        final Context app = ctx.getApplicationContext();
        synchronized (LOCK) {
            if (sTts == null) initTts(app);
        }
        playGear(app, new Cfg(app), gear);
    }

    public static String eventLabel(int ev) {
        return DOOR_NAMES[ev / 2] + (ev % 2 == 1 ? "开" : "关");
    }

    public static String gearLabel(int gear) {
        return gearName(gear);
    }

    /** 事件号 → 内置原车语音 raw 资源名（res/raw 14 条之一）。 */
    private static String builtinName(int ev) {
        String[] prefix = {"greet_main", "greet_copilot", "greet_left", "greet_right", "greet_tail"};
        return prefix[ev / 2] + (ev % 2 == 1 ? "_open" : "_close");
    }

    private static void initTts(Context ctx) {
        try {
            sTts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    sTtsReady = status == TextToSpeech.SUCCESS;
                    if (sTtsReady && sTts != null) {
                        try { sTts.setLanguage(java.util.Locale.CHINA); } catch (Throwable ignored) { }
                    } else {
                        AppLog.w(TAG, "TTS 初始化失败 status=" + status);
                    }
                }
            });
        } catch (Throwable t) {
            AppLog.w(TAG, "TTS init failed: " + t);
        }
    }

    private static void loop(Context ctx) {
        AppLog.d(TAG, "迎宾轮询线程启动");
        Cfg cfg = new Cfg(ctx);
        Vd v = Vd.connect(ctx);
        int[] prev = new int[DOOR_NAMES.length];
        java.util.Arrays.fill(prev, -1);
        int prevGear = -1;
        int lastGear = -1;
        int prevEngine = -1;
        int engineCand = -1, engineCandCnt = 0;
        int tailLatch = -1, tailLatchCand = -1, tailLatchCnt = 0;
        boolean tailLatchUsable = false;
        int failStreak = 0;
        int hbCnt = 0;
        while (sRun) {
            try { Thread.sleep(150); } catch (InterruptedException e) { return; }
            if (!v.ok()) { v = Vd.connect(ctx); continue; }
            boolean anyRead = false;
            try {
                for (int door = 0; door < DOOR_NAMES.length; door++) {
                    int cmdId = cfg.doorCmdId(door);
                    if (cmdId <= 0) { prev[door] = -1; continue; }
                    int now = v.getItem(EV_DOOR, cmdId);
                    if (now < 0) continue;
                    anyRead = true;
                    int was = prev[door];
                    prev[door] = now;
                    if (was < 0 || was == now) continue; // 首读只记基准，不播
                    AppLog.d(TAG, "车门 " + DOOR_NAMES[door] + " 327684/" + cmdId + " 状态 " + was + "→" + now);
                    if (door == 4 && tailLatchUsable) continue; // 尾门改由锁闩信号驱动（按下即播）
                    play(ctx, cfg, door * 2 + (now == 1 ? 1 : 0));
                }
                // 挡位播报：挂 P/R/N/D 播配置音频或内置语音（327684/26，1=P 2=R 3=N 4=D；
                // P/R/D 有内置，N 没配就静默；首次读数只记基准不播）
                int gear = v.getItem(EV_DOOR, CMD_GEAR);
                if (gear >= 1 && gear <= 4) {
                    anyRead = true;
                    lastGear = gear;
                    if (gear != prevGear) {
                        AppLog.d(TAG, "挡位 " + (prevGear == -1 ? "初值" : String.valueOf(prevGear)) + "→" + gear);
                        if (prevGear != -1) playGear(ctx, cfg, gear);
                        prevGear = gear;
                    }
                }
                // 发动机播报：启动/熄火播配置音频或内置语音（327684/38，首读只记基准）。
                // 用户要求点火瞬间就播：去抖只防单拍毛刺（2拍≈0.3s）；
                // 行驶中自动启停误报由 P 档守卫挡（启停只发生在 D/R 档）。
                int eng = v.getItem(EV_DOOR, CMD_ENGINE);
                if (eng == 0 || eng == 1) {
                    anyRead = true;
                    if (eng != prevEngine) {
                        if (eng != engineCand) { engineCand = eng; engineCandCnt = 1; }
                        else if (++engineCandCnt >= 2) {
                            if (prevEngine == -1) {
                                // 首个稳定值只记基准：开机时发动机本来就在某个状态，
                                // 拿它当"变化"播会误报一次熄火/启动
                                AppLog.d(TAG, "发动机基准 " + eng + "（首稳定值不播）");
                            } else {
                                // P 档守卫只用触发瞬间的全新挡位读数；挡位读不到
                                // 就当 P 档播（熄火后挡位常读不到，若回退到上次 D 档
                                // 缓存会把点火/熄火语音全压掉 —— 用户报的失语根因）。
                                // 只有新鲜的 D/R/N 读数才能压掉行驶中自动启停误报。
                                int g = v.getItem(EV_DOOR, CMD_GEAR);
                                boolean inP = !(g >= 2 && g <= 4);
                                AppLog.d(TAG, "发动机 " + (eng == 1 ? "启动" : "熄火")
                                        + "（gear=" + g + (inP ? "" : "，非P档不播") + "）");
                                if (inP) playEngine(ctx, cfg, eng == 1);
                            }
                            prevEngine = eng;
                            engineCandCnt = 0;
                        }
                    } else {
                        engineCand = eng;
                        engineCandCnt = 0;
                    }
                }
                // 尾门锁闩（327681/92）：锁闭2↔非锁闭0。变非锁闭 = 门刚被解锁还没动，
                // 此时按 162 当前状态判方向播报 —— 按下开关就播，不等锁上。
                // 92 读不到（<0）时 tailLatchUsable 不置位，回落到上面 162 翻变的老逻辑。
                int latch = v.getItem(EV_BODY, CMD_TAIL_LATCH);
                if (latch >= 0) {
                    anyRead = true;
                    tailLatchUsable = true;
                    if (latch != tailLatchCand) { tailLatchCand = latch; tailLatchCnt = 1; }
                    else if (++tailLatchCnt >= 2 && tailLatchCand != tailLatch) {
                        int was = tailLatch;
                        tailLatch = tailLatchCand;
                        tailLatchCnt = 0;
                        if (was == 2 && tailLatch != 2) {
                            boolean closing = prev[4] == 1;
                            AppLog.d(TAG, "尾门解锁（162=" + prev[4] + "）判定"
                                    + (closing ? "关闭" : "打开") + "动作，立即播报");
                            if (prev[4] >= 0) play(ctx, cfg, 8 + (closing ? 0 : 1));
                        }
                    }
                }
            } catch (Throwable t) {
                // 轮询线程绝不能被单次异常打死，否则挡位/车门全静默
                AppLog.w(TAG, "迎宾轮询异常继续: " + t);
            }
            // ~10s 心跳：实车上"完全没播"时先看这条在不在动 —— 在动说明线程活着、
            // 总线读数正常，问题在播放侧；不动说明轮询/总线死了
            if (++hbCnt >= 66) {
                hbCnt = 0;
                AppLog.d(TAG, "alive: eng=" + prevEngine + " gear=" + lastGear
                        + " doors=" + java.util.Arrays.toString(prev)
                        + " latch=" + tailLatch + (tailLatchUsable ? "(可用)" : "(不可用)")
                        + " fail=" + failStreak);
            }
            // 连续 ~9 秒一条都读不到：总线连接已死（熄火休眠的典型表现）。
            // reset 掉重连，不修的话软件就一直哑到下次点火（用户报的熄火后失联）。
            if (!anyRead && ++failStreak >= 60) {
                AppLog.w(TAG, "总线连续读数失败，重置重连");
                failStreak = 0;
                Vd.reset();
                v = Vd.connect(ctx);
                // 重连后状态可能已变，全部重新记基准，避免拿旧状态误播
                java.util.Arrays.fill(prev, -1);
                prevGear = -1;
                prevEngine = -1;
                engineCand = -1; engineCandCnt = 0;
                tailLatch = -1; tailLatchCand = -1; tailLatchCnt = 0;
            } else if (anyRead) {
                failStreak = 0;
            }
        }
    }

    private static String builtinGearName(int gear) {
        switch (gear) {
            case 1: return "greet_gear_p";
            case 2: return "greet_gear_r";
            case 3: return "greet_gear_n";
            case 4: return "greet_gear_d";
            default: return null;
        }
    }

    /** 发动机播报：先播配置的音频，没配回落内置（greet_engine_start/stop）。
     *  整体丢到播放线程跑，轮询线程和 LOCK 都不等它。 */
    private static void playEngine(final Context ctx, final Cfg cfg, final boolean start) {
        submitPlay(new Runnable() { @Override public void run() {
            String label = start ? "发动机启动" : "发动机熄火";
            String key = start ? "engine_start" : "engine_stop";
            String builtin = start ? "greet_engine_start" : "greet_engine_stop";
            stopSoundLocked();
            GreetAudio.Cfg ac = GreetAudio.parse(cfg.audio(key));
            GreetAudio.Src src = ac != null ? GreetAudio.resolve(ctx, ac.src) : null;
            if (src == null) {
                ac = new GreetAudio.Cfg();
                ac.src = "raw:" + builtin;
                src = GreetAudio.resolve(ctx, ac.src);
                if (src == null) return;
                GreetPlayer.play(ctx, ac, src.resId, src.path, null);
                AppLog.d(TAG, "播放 " + label + " 内置语音");
            } else {
                GreetPlayer.play(ctx, ac, src.resId, src.path, null);
                AppLog.d(TAG, "播放 " + label + " 音频 src=" + ac.src);
            }
        }});
    }

    public static void previewEngine(Context ctx, boolean start) {
        Context app = ctx.getApplicationContext();
        if (sTts == null) initTts(app);
        playEngine(app, new Cfg(app), start);
    }

    public static String engineLabel(boolean start) {
        return start ? "发动机启动" : "发动机熄火";
    }

    private static String gearName(int gear) {
        switch (gear) {
            case 1: return "P档";
            case 2: return "R档";
            case 4: return "D档";
            default: return "N档";
        }
    }

    /** 挡位播报：先播配置的音频，没配回落内置（P/R/D 有内置，N 没配就静默）。 */
    private static void playGear(final Context ctx, final Cfg cfg, final int gear) {
        submitPlay(new Runnable() { @Override public void run() {
            String label = gearName(gear);
            stopSoundLocked();
            GreetAudio.Cfg ac = GreetAudio.parse(cfg.audio("gear_" + gear));
            GreetAudio.Src src = ac != null ? GreetAudio.resolve(ctx, ac.src) : null;
            if (src == null) {
                String raw = builtinGearName(gear);
                if (raw == null) return;
                ac = new GreetAudio.Cfg();
                ac.src = "raw:" + raw;
                src = GreetAudio.resolve(ctx, ac.src);
                if (src == null) return;
                GreetPlayer.play(ctx, ac, src.resId, src.path, null);
                AppLog.d(TAG, "播放 " + label + " 内置语音");
            } else {
                GreetPlayer.play(ctx, ac, src.resId, src.path, null);
                AppLog.d(TAG, "播放 " + label + " 音频 src=" + ac.src);
            }
        }});
    }

    private static void play(final Context ctx, final Cfg cfg, final int ev) {
        submitPlay(new Runnable() { @Override public void run() {
            String name = eventLabel(ev);
            stopSoundLocked();
            GreetAudio.Cfg ac = GreetAudio.parse(cfg.greetAudio(ev));
            GreetAudio.Src src = ac != null ? GreetAudio.resolve(ctx, ac.src) : null;
            boolean builtin = false;
            if (src == null) {
                // 没配音频：回落到对应车门的内置原车语音（TTS 在这台车机上起不来，status=-1）
                ac = new GreetAudio.Cfg();
                ac.src = "raw:" + builtinName(ev);
                src = GreetAudio.resolve(ctx, ac.src);
                builtin = src != null;
            }
            if (src != null) {
                // 配了音频：完整按弹窗里选的参数播（通道/焦点/音量/扬声器/增益）
                GreetPlayer.play(ctx, ac, src.resId, src.path, null);
                AppLog.d(TAG, "播放 " + name + (builtin ? " 内置语音" : " 音频") + " src=" + ac.src);
            } else if (sTtsReady && sTts != null) {
                String text = cfg.greetText(ev);
                if (text != null && !text.trim().isEmpty()) {
                    try {
                        sTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "greet" + ev);
                    } catch (Throwable t) {
                        AppLog.w(TAG, "TTS 播报 " + name + " 失败: " + t);
                    }
                }
            }
        }});
    }

    private static void stopSoundLocked() {
        GreetPlayer.stop();
        if (sTts != null) { try { sTts.stop(); } catch (Throwable ignored) { } }
    }

    private static void holdCpu(Context ctx) {
        if (sWl != null) return;
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    ctx.getSystemService(Context.POWER_SERVICE);
            sWl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "clustercast:greet");
            sWl.setReferenceCounted(false);
            sWl.acquire();
        } catch (Throwable t) {
            AppLog.w(TAG, "CPU 唤醒锁拿不到，休眠时可能不播报: " + t);
        }
    }

    private static void dropCpu() {
        android.os.PowerManager.WakeLock wl = sWl;
        sWl = null;
        if (wl == null) return;
        try { if (wl.isHeld()) wl.release(); } catch (Throwable ignored) { }
    }

    /** Doze 深休眠会忽略非白名单应用的 wakelock（长睡不播的根因），没豁免就弹系统确认框。 */
    /** 申请电池优化豁免（Doze 会废掉非白名单 wakelock）。迎宾与哨兵共用。 */
    public static void ensureDozeWhitelist(Context ctx) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    ctx.getSystemService(Context.POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(ctx.getPackageName())) return;
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            AppLog.w(TAG, "电池优化豁免申请失败: " + t);
        }
    }
}
