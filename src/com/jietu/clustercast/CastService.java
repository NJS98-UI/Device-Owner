package com.jietu.clustercast;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 常驻前台服务：接收车机系统自己发出的三指手势广播。
 *
 * 该广播由 system_server 里的 CarSystemGesturesManager 用
 * sendBroadcastAsUser(intent, UserHandle.ALL) 发出，不带任何接收权限：
 *   action  = android.intent.action.car_system_gesture_mode
 *   extras  = gesture(int), display(int)
 *   gesture 编码 = 手指数*100 + 方向（0左 1右）
 * 所以 300 = 三指左滑（投屏），301 = 三指右滑（退出）。普通应用注册个接收器
 * 就能收到，不需要 root、不需要 Shizuku、也不需要开无障碍。
 *
 * v12 形态（用户纠正后的还原）：投到仪表的是「自选软件」——
 *   · 三指左滑：把当前前台应用投到仪表屏（跟随前台，拿不到就用选定的目标）
 *   · 三指右滑：退出投屏，投着的退回主屏（不杀进程），仪表还原原显示模式，
 *     同时解除对原车高德（psmap）的禁用
 *   · 投着 A 时主屏打开 B、再三指左滑：B 上仪表，A 退回主屏，可在主屏再打开
 *   · 投第三方软件期间禁用原车高德防抢仪表；退出时恢复
 *
 * v13（实车反馈四项）：
 *   1. 仪表固定导航档（v15.9 起不再提供模式选择，Cfg.castTheme 恒为 THEME_NAVI）
 *   2. 修「被原车压住」：psmap 活着时在 display 2 有全屏 overlay，谁也盖不过它。
 *      投屏开始先解禁高德让它重新启动、把 DisplayCluster=true 锁存进 QNX，
 *      等一拍再禁用 —— overlay 随进程消失，锁存还在，投屏才透得出来
 *   3. 守卫只在投屏期跑：v12 的守卫常驻，退出后跟恢复的 psmap 抢总线，
 *      原车地图还原不回去。v13 投第三方时启动，退出/投原车高德时停止
 *   4. 自选应用自动关闭跟随前台（不然左滑永远投前台，选了白选）
 *
 * v14（悬浮模式，窗口机制来自 amap-companion 的 OverlayService.ensureClusterMirror）：
 *   投第三方软件优先走「仪表悬浮层 + 虚拟屏」（ClusterOverlay）：在仪表屏自己的
 *   WindowManager 上 addView 一个 TYPE_APPLICATION_OVERLAY 全屏层 —— 同层窗口
 *   后加者在上面，必然盖过原车高德的全屏 overlay；层里的 SurfaceView 绑一块
 *   仪表同分辨率的公共虚拟屏，自选 app 用 setLaunchDisplayId 启进去。app 在
 *   虚拟屏（后台）渲染，主屏随便用；原车高德不禁用、仪表模式不动、总线不碰。
 *   缺悬浮窗权限或系统拒绝往虚拟屏启应用时，自动退回 v13 搬屏路径（那时才
 *   临时禁用高德），并把真实原因写进日志。
 */
public class CastService extends Service {

    Handler mHandler;
    /** VDBus 的 bindService/getOnce 是同步 binder，绝不能在主线程跑。 */
    Handler mWork;
    Cfg mCfg;
    long mLastGestureAt = 0L;
    private LogSink mSink = null;
    private final StringBuilder mLogBuf = new StringBuilder();
    private final SimpleDateFormat mTime = new SimpleDateFormat("HH:mm:ss", java.util.Locale.US);

    public interface LogSink { void onLog(String s); }

    /** 静态嵌套类风格的接收器：只持弱引用，服务没了就静默丢弃。 */
    public static class GestureRx extends BroadcastReceiver {
        private final WeakReference<CastService> ref;
        GestureRx(CastService s) { ref = new WeakReference<>(s); }
        @Override public void onReceive(Context context, Intent intent) {
            final CastService s = ref.get();
            if (s == null) return;
            int g = intent.getIntExtra("gesture", -1);
            int disp = intent.getIntExtra("display", -1);
            if (g != G_3_LEFT && g != G_3_RIGHT) return;
            s.log("收到三指手势");
            long now = System.currentTimeMillis();
            synchronized (s) {
                if (now - s.mLastGestureAt < DEBOUNCE_MS) return;
                s.mLastGestureAt = now;
            }
            if (disp != 0 && disp != -1) return;   // 只认主屏上的手势
            // v16：左滑=投屏，右滑=退出（用户要求改回：三指左滑进仪表，三指右滑退出）
            s.mWork.post(g == G_3_LEFT ? s.mCast : s.mExit);
        }
    }

    private final Runnable mCast = new Runnable() {
        @Override public void run() { castFromGesture(); }
    };
    private final Runnable mExit = new Runnable() {
        @Override public void run() { exitFromGesture(); }
    };

    /** 给界面上的按钮用：动作一律丢到工作线程，总线调用不能卡主线程。 */
    public void castNow() { mWork.post(mCast); }
    public void exitNow() { mWork.post(mExit); }

    /** 设置页开关：主桌面音乐卡片（代发 + 原卡片位自绘同款）。 */
    public void setMusicCard(boolean on) {
        if (on) {
            if (mMusicPublisher == null) mMusicPublisher = new MusicCardPublisher();
            mMusicPublisher.start();
            DesktopMusicCard.start(this);
        } else {
            if (mMusicPublisher != null) mMusicPublisher.stop();
            DesktopMusicCard.stop();
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        sInst = this;
        mHandler = new Handler(Looper.getMainLooper());
        HandlerThread ht = new HandlerThread("cast-work");
        ht.start();
        mWork = new Handler(ht.getLooper());
        mCfg = new Cfg(this);
        mOverlay = new ClusterOverlay(this);
        startForegroundNotice();
        // Device Owner 防杀加固（幂等）：防强行停止/防卸载/省电豁免/静默权限
        KeepAliveGuard.apply(this);
        // 仪表盘悬浮音乐开关：开着就常驻（非投屏也显示）
        if (mCfg.clusterMusic()) ClusterMusicOverlay.show(this);
        // 开门迎宾语：总开关开着就随服务常驻监听
        if (mCfg.greeting()) DoorGreeting.start(this);
        // 哨兵模式（停车守卫）：幂等自适应，开关开着就随服务常驻监听车门信号
        SentinelController.refresh(this);
        // 主桌面音乐卡片代发：云听不上报 VDMediaItem，卡片空白时代发 MediaSession 元数据
        if (mCfg.musicCard()) {
            mMusicPublisher = new MusicCardPublisher();
            mMusicPublisher.start();
            // 原车卡片只认媒体中心自带音源（网易云/云听/蓝牙/USB），第三方音源时
            // 在原卡片位置自绘同款卡片，原车音源接管时自动隐藏
            DesktopMusicCard.start(this);
        }
        registerReceiver(new GestureRx(this), new IntentFilter(GESTURE_ACTION));
        // 熄火（屏幕灭）时如果还投着屏且压着高德：自动退出投屏并恢复高德，别让它压着过夜。
        // 同时熄屏/亮屏驱动相机休眠闸门：跨休眠持有 Camera2 会话会把 HAL 卡死
        // （2026-10-02 实车：唤醒后四路黑屏，连原车倒车影像都黑，只能重启）。
        mScreenOffRx = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                String a = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    if (mCfg.amapHiddenByUs() || mCfg.amapOffByUs() || mAmapDisabledByUs) {
                        log("检测到熄火，自动退出投屏");
                        exitNow();
                    }
                    SentinelController.setScreenDark(true);
                    QuadAutoRecord.suspendForSleep(ctx);
                    // 「息屏录制」开着时 QuadAutoRecord 未挂起，其 Surround 流不得被闸门关掉
                    if (!new com.kooo.evcam.AppConfig(ctx).isScreenOffRecordingEnabled()) {
                        Surround.suspendAll();
                    }
                    MainActivity m = MainActivity.getInstance();
                    if (m != null) m.onSystemSleep();
                } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    SentinelController.setScreenDark(false);
                    Surround.resumeAll();
                    QuadAutoRecord.resumeFromSleep(ctx);
                    MainActivity m = MainActivity.getInstance();
                    if (m != null) m.onSystemWake();
                }
            }
        };
        IntentFilter sof = new IntentFilter();
        sof.addAction(Intent.ACTION_SCREEN_OFF);
        sof.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(mScreenOffRx, sof);
        mWork.post(new Runnable() {
            @Override public void run() {
                Vd v = Vd.connect(CastService.this);
                if (!v.ok()) {
                    log("总线未连接");
                    return;
                }
                log("总线已连接");
                mWork.postDelayed(mThemeRetry, 1500);
                // v13：导航区接管守卫不再常驻 —— 只在投第三方软件期间运行，
                // 退出投屏即停。常驻会和恢复后的原车高德抢总线，地图还原不回去。
            }
        });
        log("服务已启动");
        // 投屏槽位是内存态，进程重启就没了。若上次是我们禁/藏了高德但进程中途被杀，
        // 这里自愈恢复，别让高德一直躺尸。
        if (mCfg.amapOffByUs() || mCfg.amapHiddenByUs()) {
            mWork.post(new Runnable() {
                @Override public void run() {
                    mAmapDisabledByUs = mCfg.amapOffByUs();
                    restoreAmap();
                }
            });
        }
    }

    private int mThemeTries = 0;
    private final Runnable mThemeRetry = new Runnable() {
        @Override public void run() {
            Vd v = Vd.inst();
            if (v == null) return;
            int t = v.getThemeViaProxy();
            if (t < 0) t = v.getTheme();
            if (t >= 0) { mThemeSeen = t; log("当前仪表模式：" + Vd.themeName(t)); return; }
            if (++mThemeTries < 5) mWork.postDelayed(this, 1500);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotice();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        sInst = null;
        if (mScreenOffRx != null) {
            try { unregisterReceiver(mScreenOffRx); } catch (Throwable ignored) { }
            mScreenOffRx = null;
        }
        // 熄火/休眠时系统可能销毁前台服务但进程还在：此时绝不能停掉开门迎宾语
        // （停它会释放唤醒锁+杀轮询线程 → 解锁开门不再播报，要等下次点火）。
        // 真正退出走 MainActivity.exitApp 的 stopService + DoorGreeting.stop()。
        if (mMusicPublisher != null) { mMusicPublisher.stop(); mMusicPublisher = null; }
        DesktopMusicCard.stop();
        if (mOverlay != null && mCastViaOverlay) mOverlay.teardown();
        super.onDestroy();
    }

    private BroadcastReceiver mScreenOffRx;
    private MusicCardPublisher mMusicPublisher;

    // ---------- 动作 ----------

    /** 进投屏前的仪表主题，退出时要还回去；-1 表示当前不在投屏中。 */
    int mThemeBefore = -1;

    /** 仪表屏上唯一那个投屏槽位：当前投的是哪个包，null = 没投。 */
    volatile String mCastPkg = null;

    public String castingPkg() { return mCastPkg; }

    /** 是我们自己禁的高德，退出时才允许恢复，别把用户手动禁的状态改回去。 */
    boolean mAmapDisabledByUs = false;

    /** 这次投屏走的是「原车导航通道」：没开新实例，退出时也别去动主屏那份。 */
    boolean mNavFollow = false;

    /** v14 主路径组件：仪表悬浮层 + 虚拟屏投屏（借鉴 amap-companion 窗口机制）。 */
    ClusterOverlay mOverlay;

    /** 这次投屏走的是悬浮路径（成了就不用还原任何原车状态，退出只撤悬浮层）。 */
    boolean mCastViaOverlay = false;

    /** 仪表盘导航区占领守卫：周期性地往 CAR_LAN 总线发状态，防止高德后台吐状态盖住投屏内容。 */
    private volatile Runnable mNavGuardTick = null;
    private final Object mNavGuardLock = new Object();

    /**
     * 三指左滑：把「当前前台应用」投到仪表屏，仪表永远只有一个槽位。
     *   · 只有滑动才换，主屏点应用绝不自作主张投屏
     *   · 已投着 A 再投 B：A 退回主屏（不 finish、不杀进程，酷狗的歌不停），B 上仪表
     *   · 退到主屏的 A 之后照常能点
     *   · 右滑退出：投着的退回主屏，仪表还原原模式
     */
    void castFromGesture() {
        String[] t = target();
        if (t == null) { log("还没选投屏应用，先打开「仪表投屏」选一个"); return; }
        if (getPackageName().equals(t[0])) { log("前台是我们自己的设置页，不投它"); return; }
        if (t[0].equals(mCastPkg)) { log("「" + label(t[0]) + "」已经在仪表上了"); return; }
        if (t[0].equals(Caster.AMAP)) {
            // 投的就是原车高德：走原车导航通道，不搬窗口，仪表跟着主屏走。
            // 原车高德自己管理仪表，守卫必须先停，别跟它抢总线（v13）。
            stopNavGuard();
            if (mCastViaOverlay) {
                mOverlay.teardown();
                mCastViaOverlay = false;
                mCastPkg = null;
                log("已撤掉仪表悬浮层，交还原车导航通道");
            }
            switchThemeForCast(Vd.THEME_NAVI);
            restoreAmap();
            retireCurrent();
            openNavChannel();
            mNavFollow = true;
            mCastPkg = t[0];
            log("已投屏 " + label(t[0]));
            return;
        }
        // 实车已验证：高德的全屏 overlay 谁也盖不住，悬浮层也不行 ——
        // 不管走悬浮还是搬屏，都得先把高德压掉（Device Owner 隐藏，退出自动恢复）
        disableAmapForCast();
        // v14 主路径：仪表悬浮层 + 虚拟屏 —— app 在虚拟屏（后台）渲染，主屏随便用。
        // 返回 false=预检失败才走降级。
        if (tryOverlayCast(t)) return;
        fallbackCast(t);
    }

    /**
     * v14 主路径尝试。返回 false=悬浮路径预检就过不了（没权限/没仪表屏），
     * 调用方直接走降级；返回 true=已受理，成败走异步回调 onOverlayCastResult。
     * v15：已投着 A（悬浮态）再投 B 时，同样把 A 退回主屏 —— B 上仪表、
     * A 在主屏照常打开，不能让 A 留在虚拟屏后台。
     */
    private boolean tryOverlayCast(final String[] t) {
        String e = ClusterOverlay.precheck(this);
        if (e != null) { log("悬浮路径用不了：" + e); return false; }
        if (mCastViaOverlay) {
            // 悬浮→悬浮切换：必须先把 A 挪回主屏、再撤悬浮层。
            // 顺序反了的话 teardown 会连虚拟屏一起销掉，A 的任务随之死亡，
            // retireCurrent 的 moveToDisplay 就扑空了 —— A 彻底消失而非退回主屏。
            mCastViaOverlay = false;
            retireCurrent();
            mOverlay.teardown();
        } else {
            // 上一次投屏的守卫/主题残留先清掉（高德是本次 castFromGesture 刚压制的，
            // 这里绝不能 restoreAmap —— 那会把刚压掉的 overlay 又放回来）
            stopNavGuard();
            retireCurrent();
            restoreTheme();
        }
        mOverlay.cast(t[0], t[1], new ClusterOverlay.Callback() {
            @Override public void onResult(boolean ok, String err) {
                mWork.post(new Runnable() {
                    @Override public void run() { onOverlayCastResult(t, ok, err); }
                });
            }
        });
        return true;
    }

    /** 悬浮投屏的异步结果：成了记账，败了撤干净自动降级到 v13 搬屏。 */
    private void onOverlayCastResult(String[] t, boolean ok, String err) {
        if (ok) {
            mCastViaOverlay = true;
            mCastPkg = t[0];
            // 投屏悬浮层后挂上来会压住音乐卡片，重排一次让音乐留在最上
            ClusterMusicOverlay.bringToFront();
            log("已投屏 " + label(t[0]));
            mainToHome();
            return;
        }
        log("悬浮投屏失败：" + err);
        // 无论之前是不是悬浮态，失败都要把悬浮层（含黑色 panel）从仪表屏上撤掉，
        // 否则残留的全屏 overlay 会盖住 v13 直投上去的应用 → 看起来还是黑屏。
        mOverlay.teardown();
        mCastViaOverlay = false;
        mCastPkg = null;
        log("自动改用搬屏投屏");
        fallbackCast(t);
    }

    /** v13 搬屏降级路径：app 搬到仪表 display 2 + 临时禁用高德防压屏，退出时还原。 */
    private void fallbackCast(String[] t) {
        switchThemeForCast(-1);
        retireCurrent();
        startNavGuard();
        applyCastFill();
        disableAmapForCast();
        String err = Caster.startOnDisplay(this, t[0], t[1], Caster.CLUSTER);
        if (err != null) {
            String e2 = Caster.startOnDisplay(this, t[0], t[1], Caster.CLUSTER, true);
            if (e2 == null) log("改整实例搬屏");
            err = e2;
        }
        if (err == null) {
            mCastViaOverlay = false;
            mCastPkg = t[0];
            log("已投屏 " + label(t[0]));
            mainToHome();
            // 部分 ROM 上 setLaunchDisplayId 后窗口没有立刻到前台，延迟一拍再搬一次确保可见。
            final String pkg0 = t[0], cls0 = t[1];
            mWork.postDelayed(new Runnable() {
                @Override public void run() {
                    if (mCastPkg != null && mCastPkg.equals(pkg0) && !mCastViaOverlay) {
                        String e = Caster.startOnDisplay(CastService.this, pkg0, cls0, Caster.CLUSTER, true);
                        if (e != null) log("二次置顶失败：" + e);
                    }
                }
            }, 800);
        } else {
            log("投屏失败 " + label(t[0]) + "：" + err);
            log("仪表屏状态：" + Caster.describeDisplays(this));
        }
    }

    /**
     * 投屏成功后把主屏送回桌面：被投应用只留在仪表，主屏不留它的画面。
     * 只是把任务压到后台，不杀进程——音乐照放，回主屏点开照常用。
     * 投原车高德（导航跟随）不走这里，主屏地图必须留着。
     */
    private void mainToHome() {
        // 延迟一拍：等虚拟屏那头启动落定，别跟系统搬任务撞车
        mWork.postDelayed(new Runnable() {
            @Override public void run() {
                if (mCastPkg == null) return;
                try {
                    Intent i = new Intent(Intent.ACTION_MAIN);
                    i.addCategory(Intent.CATEGORY_HOME);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ActivityOptions o = ActivityOptions.makeBasic();
                    o.setLaunchDisplayId(Caster.MAIN);
                    startActivity(i, o.toBundle());
                    log("主屏已回桌面，" + label(mCastPkg) + " 只在仪表显示");
                } catch (Throwable t) {
                    log("主屏回桌面失败：" + t);
                }
            }
        }, 700);
    }

    /** 把上一个投在仪表上的应用退回主屏，不杀进程。 */
    private void retireCurrent() {
        String prev = mCastPkg;
        mCastPkg = null;
        boolean follow = mNavFollow;
        mNavFollow = false;
        if (prev == null) return;
        if (follow) { closeNavChannel(); log("原车导航通道已断开"); return; }
        if (Caster.moveToDisplay(this, prev, Caster.MAIN)) {
            log(label(prev) + " 已退回主屏（没杀进程）");
        } else {
            log(label(prev) + " 退回主屏失败，可能还留在仪表上");
        }
    }

    /**
     * 按界面上选的那一档切仪表模式，并回读校验。force 非空时用它覆盖选择
     * （原车导航通道必须是导航档，极简档下仪表不画地图）。
     * 极简档 = 仪表基本不画东西，投上去的软件就是整块画面。
     */
    private void switchThemeForCast(int force) {
        int want = force > 0 ? force : mCfg.castTheme();
        Vd v = Vd.connect(this);
        if (!v.ok()) { log("总线没连上，直接投屏"); return; }
        if (mThemeBefore < 0) mThemeBefore = readTheme(v);
        v.lastPath = 0;
        v.setTheme(want);
        int back = readBack(v);
        mThemeSeen = back;
        String name = Vd.themeName(want);
        if (back == want) {
            log("仪表已切到" + name);
        } else {
            log("切" + name + "没生效");
        }
    }

    /**
     * 打开/关闭原车导航投屏通道（CAR_LAN 721699 + 721702）。
     * 这两条就是原车自己在发的：导航投屏不搬窗口，仪表由 psmap 的渲染器订阅总线来画，
     * 所以主屏导航照常，仪表同时跟随。总线拒了就把真实原因写日志，绝不假报成功。
     */
    private void openNavChannel() {
        Vd v = Vd.connect(this);
        if (!v.ok()) { log("总线没连上，导航通道开不了"); return; }
        String e1 = v.publishNaviCluster(true);
        String e2 = v.publishNaviArea(true);
        if (e1 != null || e2 != null)
            log("仪表导航切换失败");
        else log("仪表导航切换成功");
    }

    private void closeNavChannel() {
        Vd v = Vd.inst();
        if (v == null || !v.ok()) return;
        v.publishNaviCluster(false);
        v.publishNaviArea(false);
    }

    /**
     * 启动「仪表导航区接管守卫」。周期性地往 CAR_LAN 总线发送：
     *   • 721699 VDNaviDisplayCluster (DisplayCluster="true")
     *   • 721702 VDNaviDisplayArea (NaviDisplayArea=1)
     *   • 721698 VDNaviRoadInfo (segRemainDis=0, roadName="ClusterCast", progress=0)
     *   • 721701 VDNaviDigitalInfo (TBT 清空：cameraType=3, naviStatus=4)
     * 防止高德后台吐「地图信息准备中」盖住投屏。投原车高德时放行。
     * v13：只在投第三方软件期间运行；退出投屏 / 投原车高德时 stopNavGuard() 停掉，
     * 让恢复后的 psmap 独占总线把原车地图画回去。
     */
    private void startNavGuard() {
        if (!mCfg.suppressNaviCluster()) return;
        synchronized (mNavGuardLock) {
            if (mNavGuardTick != null) return;
            mNavGuardTick = new Runnable() {
                @Override public void run() {
                    if (mNavFollow) { mWork.postDelayed(this, 3000); return; }  // 投原车导航时放行
                    Vd v = Vd.inst();
                    if (v != null && v.ok()) {
                        String e0 = v.publishNaviDisplayJson(true);          // DisplayCluster=true
                        String e1 = v.publishNaviAreaNormal();               // Area=1
                        String e2 = v.publishNaviRoad(0, 0, 0, "ClusterCast", "Ready", 0, 0);
                        String e3 = v.publishNaviDigital(3, 4, 0, -1);       // TBT cleared
                        if (e0 != null || e1 != null || e2 != null || e3 != null)
                            log("导航区接管失败");
                    }
                    mWork.postDelayed(this, 3000);
                }
            };
            mWork.post(mNavGuardTick);
        }
        log("仪表导航区接管已启动");
    }

    /**
     * v13：停掉导航区接管守卫。退出投屏 / 投原车高德时调用 ——
     * 高德恢复后要自己往总线吐状态画地图，守卫再发假数据就是抢它的总线，
     * 「退出还原车地图」就不成立了。removeCallbacks 把排队的下一次 tick 一并撤销。
     */
    private void stopNavGuard() {
        synchronized (mNavGuardLock) {
            if (mNavGuardTick == null) return;
            mWork.removeCallbacks(mNavGuardTick);
            mNavGuardTick = null;
        }
        log("仪表导航区接管已停止");
    }

    /**
     * 投屏第三方软件时压制原车高德（用户要求：投屏压制、退出解除，全自动对称操作）。
     * 实车已验证：高德的全屏 overlay 盖不住（我们的悬浮层压不上），必须让它消失。
     *
     * 两条路径：
     *   1. Device Owner（首选）：setApplicationHidden 隐藏 —— set-device-owner 是
     *      一次性 adb 授权，重启不丢。隐藏会把高德进程杀掉，overlay 随之消失。
     *   2. 旧 API setApplicationEnabledSetting：对第三方包需要 signature|privileged
     *      权限，普通应用必被拒（实车已验证），失败如实记录。
     *
     * v13 时序对两条路径都适用 —— 若高德此刻处于我们压制的状态（进程重启后自愈
     * 还没跑到等），必须「先释放 → 等 1.8 秒 → 再压制」：释放让 psmap 重新启动并往
     * QNX 锁存 DisplayCluster=true（仪表矩形通道打开），再压掉它让 overlay 消失 ——
     * 锁存还在，投屏画面才透得出来；对一个已死的高德「压制」等于什么都没做。
     */
    private void disableAmapForCast() {
        if (mAmapDisabledByUs || mCfg.amapHiddenByUs()) return;
        boolean wasHidden = mCfg.amapHiddenByUs();
        boolean wasDisabled = mCfg.amapOffByUs();
        if (wasHidden || wasDisabled) {
            // 先释放，让高德活过来把仪表通道重新锁存
            if (wasHidden) {
                String e = setAmapHidden(false);
                log(e == null ? "正在重新禁用原车高德" : "禁用原车高德失败：" + e);
            } else {
                String e = Caster.setAmapEnabled(this, true);
                log(e == null ? "正在重新禁用原车高德" : "禁用原车高德失败：" + e);
            }
            if (mCfg.amapHiddenByUs()) mCfg.setAmapHiddenByUs(false);
            if (mCfg.amapOffByUs()) mCfg.setAmapOffByUs(false);
            try { Thread.sleep(1800); } catch (InterruptedException ignored) { }
        }
        if (isDeviceOwner()) {
            String e = setAmapHidden(true);
            if (e == null) {
                mCfg.setAmapHiddenByUs(true);
                log("禁用原车高德成功");
            } else {
                log("禁用原车高德失败：" + e);
            }
            return;
        }
        String err = Caster.setAmapEnabled(this, false);
        if (err == null) {
            mAmapDisabledByUs = true;
            mCfg.setAmapOffByUs(true);
            log("禁用原车高德成功");
        } else {
            log("禁用原车高德失败：" + err);
        }
    }

    /** 恢复原车高德。按当时实际用的路径（隐藏/禁用）对称解除；没压过就什么都不动。 */
    private void restoreAmap() {
        boolean hidden = mCfg.amapHiddenByUs();
        boolean disabled = mAmapDisabledByUs || mCfg.amapOffByUs();
        if (!hidden && !disabled) return;
        mAmapDisabledByUs = false;
        if (mCfg.amapHiddenByUs()) mCfg.setAmapHiddenByUs(false);
        if (mCfg.amapOffByUs()) mCfg.setAmapOffByUs(false);
        if (hidden) {
            String e = setAmapHidden(false);
            log(e == null ? "已恢复原车高德" : "恢复原车高德失败");
            // 解除隐藏只是恢复包可见性，进程不会自己起来 —— 实车验证：不拉起的话
            // 仪表永远停在「地图信息准备中」。主动把高德主界面拉起来，几秒内地图还原。
            if (e == null) relaunchAmap();
            return;
        }
        String err = Caster.setAmapEnabled(this, true);
        log(err == null ? "已恢复原车高德" : "恢复高德失败：" + err);
    }

    /** 把原车高德进程拉起来（解除隐藏/禁用后它不会自启，仪表地图要等它重新锁存）。 */
    private void relaunchAmap() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(Caster.AMAP);
            if (i == null) {
                log("高德没有可启动的入口，等它下次自启");
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            log("已拉起原车高德");
        } catch (Throwable t) {
            log("拉起原车高德失败：" + t);
        }
    }

    // ---------- Device Owner：隐藏/恢复原车高德 ----------

    private android.content.ComponentName adminCn() {
        return new android.content.ComponentName(this, CastAdminReceiver.class);
    }

    boolean isDeviceOwner() {
        try {
            android.app.admin.DevicePolicyManager dpm =
                    getSystemService(android.app.admin.DevicePolicyManager.class);
            return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** setApplicationHidden(true 会杀掉高德进程，overlay 随之消失)。返回 null=成功。 */
    private String setAmapHidden(boolean hidden) {
        try {
            android.app.admin.DevicePolicyManager dpm =
                    getSystemService(android.app.admin.DevicePolicyManager.class);
            if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName()))
                return "还不是 Device Owner（先 adb shell dpm set-device-owner 授权一次）";
            boolean ok = dpm.setApplicationHidden(adminCn(), Caster.AMAP, hidden);
            return ok ? null : "系统返回 false（包不存在或不接受）";
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 三指右滑：投着的退回主屏，仪表退回原显示模式，解除压制的高德。 */
    void exitFromGesture() {
        if (mCastViaOverlay) {
            // 悬浮模式退出：先把投着的 app 挪回主屏（不然撤虚拟屏连任务一起杀掉，
            // 用户就再也打不开它了），再撤悬浮层、销虚拟屏。
            // 进投屏前压掉的高德在这里对称恢复。
            String prev = mCastPkg;
            if (prev != null && Caster.moveToDisplay(this, prev, Caster.MAIN)) {
                log(label(prev) + " 已退回主屏（没杀进程）");
            }
            mCastPkg = null;
            mCastViaOverlay = false;
            mOverlay.teardown();
            restoreAmap();
            log(prev != null
                    ? "已退出投屏（" + label(prev) + " 已回主屏，仪表还回原车内容）"
                    : "已退出投屏");
            return;
        }
        stopNavGuard();
        retireCurrent();
        // 兜底：投屏状态丢了（进程重启过）但悬浮层还挂着时也一并撤掉 ——
        // 「点结束投屏就退出所有软件的投屏」，仪表必须回到投屏之前的模式
        mOverlay.teardown();
        restoreTheme();
        restoreAmap();
        log("已退出投屏");
    }

    private void restoreTheme() {
        int back = mThemeBefore;
        mThemeBefore = -1;
        if (back < 0) return;
        Vd v = Vd.inst();
        if (v == null) return;
        v.setTheme(back);
        mThemeSeen = readBack(v);
    }

    private int readTheme(Vd v) {
        int t = v.getThemeViaProxy();
        if (t < 0) t = v.getTheme();
        mThemeSeen = t;
        return t;
    }

    /** 界面上显示的当前仪表主题，由工作线程刷新。 */
    volatile int mThemeSeen = -1;

    public int themeSeen() { return mThemeSeen; }

    /**
     * 整屏填充：startOnDisplay 里已把仪表屏整块物理区域写成 launchBounds，
     * 这里把要写的区域照原样记进日志。这台 ROM 忽略 launchBounds 的话，
     * 投完还是原来的小窗，日志一对尺寸就知道 —— 绝不用「已铺满」三个字糊过去。
     */
    void applyCastFill() {
        if (!mCfg.castFill()) return;
        Rect r = Caster.fullBounds(this, Caster.CLUSTER);
        log(r == null ? "没读到仪表屏尺寸，这次投屏沿用系统默认区域"
                : "投屏区域已写成仪表整屏 " + r.width() + "x" + r.height()
                        + "（Z 序由系统决定，量出来没铺满会照实写）");
    }

    /** 优先跟随前台应用（需使用情况访问权限），没授权就用上次选定的目标。 */
    String[] target() {
        if (mCfg.followTop()) {
            String pkg = TopApp.get(this);
            if (pkg != null && !pkg.equals(getPackageName())) {
                String cls = Caster.launchable(this, pkg);
                if (cls != null) return new String[]{pkg, cls};
                log(pkg + " 没有可启动的界面，改用上次的目标");
            } else {
                log(TopApp.granted(this)
                        ? "没抓到前台应用（桌面或自身），改用上次的目标"
                        : "没给使用情况访问权限，取不到前台，改用上次的目标");
            }
        }
        String pkg = mCfg.pkg();
        String cls = mCfg.cls();
        if (pkg == null || cls == null) return null;
        return new String[]{pkg, cls};
    }

    public String label(String pkg) {
        try {
            android.content.pm.ApplicationInfo ai =
                    getPackageManager().getApplicationInfo(pkg, 0);
            return getPackageManager().getApplicationLabel(ai).toString();
        } catch (Throwable t) { return pkg; }
    }

    // ---------- 日志 ----------

    public void setSink(LogSink s) {
        mSink = s;
        if (s != null) s.onLog(mLogBuf.toString());
    }

    public void log(String s) {
        Log.i(TAG, s);
        String line = mTime.format(new Date()) + "  " + s + "\n";
        synchronized (mLogBuf) {
            mLogBuf.append(line);
            if (mLogBuf.length() > 8000) mLogBuf.delete(0, mLogBuf.length() - 6000);
        }
        final LogSink sink = mSink;
        if (sink == null) return;
        mHandler.post(new Runnable() {
            @Override public void run() { sink.onLog(mLogBuf.toString()); }
        });
    }

    public String logText() {
        synchronized (mLogBuf) { return mLogBuf.toString(); }
    }

    /** 清空日志缓冲。 */
    public void clearLog() {
        synchronized (mLogBuf) { mLogBuf.setLength(0); }
        final LogSink sink = mSink;
        if (sink != null) {
            mHandler.post(new Runnable() {
                @Override public void run() { sink.onLog(""); }
            });
        }
    }

    private void startForegroundNotice() {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String ch = "cast";
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                    new NotificationChannel(ch, "仪表投屏", NotificationManager.IMPORTANCE_MIN));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        b.setContentTitle("仪表投屏运行中")
                .setContentText("三指左滑投屏 · 三指右滑退出")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pi);
        startForeground(42, b.build());
    }

    static final String TAG = "ClusterCast";
    static final String GESTURE_ACTION = "android.intent.action.car_system_gesture_mode";
    static final int G_3_LEFT = 300;
    static final int G_3_RIGHT = 301;
    private static final long DEBOUNCE_MS = 800L;

    /** 原车下发后也是延迟回读的，总线要一点时间才落到 MCU。 */
    static int readBack(Vd v) {
        try { Thread.sleep(250); } catch (InterruptedException ignored) { }
        int t = v.getThemeViaProxy();
        if (t >= 0) return t;
        t = v.getTheme();
        if (t >= 0) return t;
        try { Thread.sleep(400); } catch (InterruptedException ignored) { }
        t = v.getThemeViaProxy();
        return t >= 0 ? t : v.getTheme();
    }

    static String pathName(int p) {
        switch (p) {
            case 3: return "VDBus+Proxy";
            case 2: return "Proxy";
            case 1: return "VDBus";
            default: return "无";
        }
    }

    private static volatile CastService sInst = null;

    public static CastService inst() { return sInst; }

    public static void start(Context c) {
        Intent i = new Intent(c, CastService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }
}
