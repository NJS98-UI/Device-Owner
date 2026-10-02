package com.ahui.clustercast;

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

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;

/**
 * 常驻前台服务：接收车机系统自己发出的三指手势广播。
 *
 * 该广播由 system_server 里的 CarSystemGesturesManager 用
 * sendBroadcastAsUser(intent, UserHandle.ALL) 发出，不带任何接收权限：
 *   action  = android.intent.action.car_system_gesture_mode
 *   extras  = gesture(int), display(int)
 *   gesture 编码 = 手指数*100 + 方向（0左 1右 2下 3上 4捏合）
 * 所以 300 = 三指左滑，301 = 三指右滑。普通应用注册个接收器就能收到，
 * 不需要 root、不需要 Shizuku、也不需要开无障碍。
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

    /**
     * 息屏锁车录制（EVCam screen_off_recording 的自写版）：
     * 熄屏延时自动开录、亮屏延时自动收尾。开关关着就什么都不做。
     * 记录仪页没开过时走 dvrAutoStart 的无界面格子，熄屏也能录。
     */
    public static class ScreenRx extends BroadcastReceiver {
        private final WeakReference<CastService> ref;
        ScreenRx(CastService s) { ref = new WeakReference<>(s); }
        @Override public void onReceive(Context context, Intent intent) {
            final CastService s = ref.get();
            if (s == null) return;
            final boolean off = Intent.ACTION_SCREEN_OFF.equals(intent.getAction());
            if (!s.mCfg.dvrScreenOff()) return;
            // EVCam 同款 10 秒延时：给锁车动作留时间，也避免亮屏查看时立刻断录。
            s.mWork.postDelayed(new Runnable() {
                @Override public void run() {
                    if (off && !s.cams.isRecording()) {
                        s.log("息屏：延时到，自动开录");
                        s.cams.dvrAutoStart();
                    } else if (!off && s.cams.isRecording()) {
                        s.log("亮屏：自动收尾录像");
                        s.cams.stopRecording();
                    }
                }
            }, off ? SCREEN_OFF_DELAY_MS : SCREEN_ON_DELAY_MS);
        }
    }

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
            s.log("收到三指手势 " + g + "（来源屏 " + disp + "）");
            long now = System.currentTimeMillis();
            synchronized (s) {
                if (now - s.mLastGestureAt < DEBOUNCE_MS) return;
                s.mLastGestureAt = now;
            }
            if (disp != 0 && disp != -1) return;   // 只认主屏上的手势
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

    /** 授权页拿到 token 后回调：现在真上镜像。 */
    public void mirrorNow() { mWork.post(mMirror); }

    private final Runnable mMirror = new Runnable() {
        @Override public void run() { castMirror(); }
    };

    /**
     * 镜像模式下的三指左滑：有 token 直接上，没 token 先弹一次系统录屏授权。
     * 契约不变：还是单槽 —— 镜像占了槽位时再滑也只是换镜像自己的页面。
     */
    private void castMirrorEntry() {
        if (mCastPkg != null && mCastPkg.equals(getPackageName()) && MirrorActivity.isAlive()) {
            log("整屏镜像已经在仪表上"); return;
        }
        if (!MirrorTok.has()) {
            mHandler.post(new Runnable() {
                @Override public void run() {
                    try {
                        startActivity(new Intent(CastService.this, MirrorGrantActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        log("系统会弹一次「录屏授权」，点「开始录制」后自动上镜像");
                    } catch (Throwable t) {
                        log("授权页弹不出来：" + t.getClass().getSimpleName() + " " + t.getMessage());
                    }
                }
            });
            return;
        }
        mWork.post(mMirror);
    }

    /** 镜像上仪表（工作线程）。走和投自己页面完全相同的槽位/主题流程。 */
    void castMirror() {
        switchThemeForCast(-1);
        retireCurrent();
        dockHide();
        String e = Caster.startOnDisplay(this, getPackageName(),
                MirrorActivity.class.getName(), Caster.CLUSTER, true);
        if (e == null) {
            mCastPkg = getPackageName();
            log("已投屏 整屏镜像（主屏画面直通仪表屏，Surface 零拷贝）");
        } else log("镜像页起不来：" + e);
    }

    /** 界面上直接拨「沉浸dock」开关时用这条：当场收起或当场还原。 */
    public void dockApply(final boolean on) {
        mWork.post(new Runnable() {
            @Override public void run() { if (on) dockForceHide(); else dockRestore(); }
        });
    }

    /** 按钮语义是「现在就沉浸」，所以不受投屏期只记一次的限制。 */
    private void dockForceHide() { mDockLogged = false; dockHide(); }

    private final CarCtl.LogCallback mLog = new CarCtl.LogCallback() {
        @Override public void log(String s) { CastService.this.log(s); }
    };

    @Override public void onCreate() {
        super.onCreate();
        sInst = this;
        mHandler = new Handler(Looper.getMainLooper());
        HandlerThread ht = new HandlerThread("cast-work");
        ht.start();
        mWork = new Handler(ht.getLooper());
        mCfg = new Cfg(this);
        carCtl = new CarCtl(this, mHandler, mLog);
        cams = new CamCtl(this, mLog);
        cams.loopMinutes = mCfg.loopMin();
        startForegroundNotice();
        registerReceiver(new GestureRx(this), new IntentFilter(GESTURE_ACTION));
        IntentFilter scrF = new IntentFilter();
        scrF.addAction(Intent.ACTION_SCREEN_OFF);
        scrF.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(new ScreenRx(this), scrF);
        if (mCfg.dvrFloat()) RecFloat.show(this);
        mWork.post(new GearTick(this));
        mWork.post(new Runnable() {
            @Override public void run() {
                Vd v = Vd.connect(CastService.this);
                if (!v.ok()) {
                    log("车身总线连不上，投屏仍可用（" + v.bindReport + "）");
                    return;
                }
                log("已连上车身总线：" + v.bindReport);
                mWork.postDelayed(mThemeRetry, 1500);
                // 覆盖层要自己画车速和档位，先把 VEHICLE_HAL 的 519/1024 订阅上。
                // 订阅失败不影响投屏本身，但必须把真实原因写出来，不能默默没有。
                String he = v.watchHud();
                if (he == null) log("已订阅仪表车速/档位");
                else log("订阅车速/档位失败：" + he);
                // 总线刚连上就先压一次，再把周期压制守卫挂上，防止高德后台吐状态占仪表导航区
                startNavGuard();
            }
        });
        log("服务已启动，正在监听三指手势广播");
        // 开机自动录像（盯盯车 auto_start_recording 同款）：用户自己点开的开关。
        // 没给 CAMERA 授权 / 镜头全被原车占着都会照实写日志，不假装在录。
        if (mCfg.dvrAutoBoot()) {
            mWork.postDelayed(new Runnable() {
                @Override public void run() { cams.dvrAutoStart(); }
            }, 20_000);
        }
        // 投屏槽位是内存态，进程重启就没了。v4.2.x 之前我们会在投屏期间禁用原车
        // psmap 来抢通道，一禁一解会把它的总线订阅打断 —— 这就是重启后必须
        // 「再解除禁用再禁用」才显示的真正原因。现在改成走原车导航通道，
        // 绝不再禁 psmap；这里只剩一件事：把老版本可能留下的禁用状态还原掉。
        if (mCfg.amapOffByUs()) {
            mWork.post(new Runnable() {
                @Override public void run() { mAmapDisabledByUs = true; restoreAmap(); }
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
        MirrorActivity.closeAll();
        MirrorTok.releaseAll();      // 服务没了绝不能留一个还在截屏的 MediaProjection
        RecFloat.hide(this);
        try { cams.destroy(); } catch (Throwable t) { }
        try { carCtl.close(); } catch (Throwable t) { }
        super.onDestroy();
    }

    // ---------- 动作 ----------

    /** 进投屏前的仪表主题，退出时要还回去；-1 表示当前不在投屏中。 */
    int mThemeBefore = -1;

    /** 仪表屏上唯一那个投屏槽位：当前投的是哪个包，null = 没投。 */
    volatile String mCastPkg = null;

    /** 是我们自己禁的高德，退出时才允许恢复，别把用户手动禁的状态改回去。 */
    boolean mAmapDisabledByUs = false;

    /** 这次投屏走的是「原车导航通道」：没开新实例，退出时也别去动主屏那份。 */
    boolean mNavFollow = false;

    /** 仪表盘导航区占领守卫：周期性地往 CAR_LAN 总线发 CLOSE，防止高德后台吐状态盖住投屏内容。 */
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
        // 三指左滑默认走整屏镜像：MediaProjection 截主屏 → VirtualDisplay 直出到仪表屏，
        // 零拷贝、不依赖任何应用、不受导航占位符影响。
        log("三指左滑：启动整屏镜像（主屏画面直通仪表屏）");
        castMirrorEntry();
        // castMirrorEntry() 失败时（无授权/授权过期），继续走到下面的降级路径。
        if (mCastPkg != null && mCastPkg.equals(getPackageName())) return;
        // 降级：按当前前台应用投屏（和旧行为一致）
        String[] t = target();
        if (t == null) { log("还没选投屏应用，先打开「仪表投屏」选一个"); return; }
        if (getPackageName().equals(t[0])) { log("前台是我们自己的设置页，不投它"); return; }
        if (t[0].equals(mCastPkg)) { log("「" + label(t[0]) + "」已经在仪表上了"); return; }
        if (t[0].equals(Caster.AMAP)) {
            switchThemeForCast(Vd.THEME_NAVI);
            restoreAmap();
            retireCurrent();
            openNavChannel();
            dockHide();
            mNavFollow = true;
            mCastPkg = t[0];
            log("已投屏 " + label(t[0]) + "（原车导航通道，仪表跟随主屏）");
            return;
        }
        switchThemeForCast(-1);
        retireCurrent();
        applyCastFill();
        dockHide();
        String e0 = Caster.startOnDisplay(this, getPackageName(),
                ClusterActivity.class.getName(), Caster.CLUSTER, true);
        if (e0 == null) {
            mCastPkg = getPackageName();
            log("已投屏 " + label(t[0]) + "（极简档：仪表上是我们的播放页）");
            return;
        }
        log("极简页起不来：" + e0 + "，改投第三方实例");
        String err = Caster.startOnDisplay(this, t[0], t[1], Caster.CLUSTER);
        if (err != null) {
            String e2 = Caster.startOnDisplay(this, t[0], t[1], Caster.CLUSTER, true);
            if (e2 == null) log("独立实例起不来，改整实例搬屏（主屏那份会跟过去）");
            err = e2;
        }
        if (err == null) {
            mCastPkg = t[0];
            log("已投屏 " + label(t[0]));
        } else {
            log("投屏失败 " + label(t[0]) + "：" + err);
            log("仪表屏状态：" + Caster.describeDisplays(this));
        }
    }

    /** 把上一个投在仪表上的应用退回主屏，不杀进程。 */
    private void retireCurrent() {
        String prev = mCastPkg;
        mCastPkg = null;
        boolean follow = mNavFollow;
        mNavFollow = false;
        if (prev == null) return;
        if (follow) { closeNavChannel(); log("原车导航通道已断开，仪表还原原模式"); return; }
        if (getPackageName().equals(prev)) {
            ClusterActivity.closeAll(); MirrorActivity.closeAll(); return;
        }
        if (Caster.moveToDisplay(this, prev, Caster.MAIN)) {
            log(label(prev) + " 已退回主屏（没杀进程）");
        } else {
            log(label(prev) + " 退回主屏失败，可能还留在仪表上");
        }
    }

    /**
     * 按界面上选的那一档切仪表模式，并回读校验。force 非空时用它覆盖选择
     * （原车导航通道必须是导航档，极简档下仪表不画地图）。
     * 导航档 = 让原车导航画面占住仪表（再配禁用高德防它抢投屏）；
     * 极简档 = 仪表基本不画东西，我们的页面盖上去当背景。
     */
    private void switchThemeForCast(int force) {
        int want = force > 0 ? force : mCfg.castTheme();
        Vd v = Vd.connect(this);
        if (!v.ok()) { log("总线没连上，仪表模式不变，直接把页面放上去"); return; }
        if (mThemeBefore < 0) mThemeBefore = readTheme(v);
        v.lastPath = 0;
        v.setTheme(want);
        int back = readBack(v);
        mThemeSeen = back;
        String name = Vd.themeName(want);
        if (back == want) {
            log("仪表已切到" + name + "（原=" + Vd.themeName(mThemeBefore)
                    + "，通道=" + pathName(v.lastPath) + "）");
        } else {
            log("切" + name + "没生效，仪表还停在" + Vd.themeName(back)
                    + "（通道=" + pathName(v.lastPath) + "）");
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
            log("导航投屏通道下发有问题：721699=" + (e1 == null ? "已发" : e1)
                    + "，721702=" + (e2 == null ? "已发" : e2));
        else log("已向总线发出「导航上仪表」请求（721699+721702）");
    }

    private void closeNavChannel() {
        Vd v = Vd.inst();
        if (v == null || !v.ok()) return;
        v.publishNaviCluster(false);
        v.publishNaviArea(false);
    }

    /**
     * 启动/重启「仪表导航区接管守卫」。周期性地往 CAR_LAN 总线发送：
     *   • 721699 VDNaviDisplayCluster (DisplayCluster="true", NaviFrontDeskStatus="true")
     *   • 721702 VDNaviDisplayArea (NaviDisplayArea=1)
     *   • 721698 VDNaviRoadInfo (segRemainDis=0, roadName="ClusterCast", progress=0)
     *   • 721701 VDNaviDigitalInfo (TBT 清空：cameraType=3, naviStatus=4)
     * QNX 会正常渲染导航画面（不是"准备中"），并且显示我们的内容——我们成为了合法的
     * 导航数据源。投屏高德时自动切换到旧版 openNavChannel() 通道即可。
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
                            log("导航区接管失败：" + (e0 == null?"ok":e0)
                                    + " / " + (e1 == null?"ok":e1)
                                    + " / " + (e2 == null?"ok":e2)
                                    + " / " + (e3 == null?"ok":e3));
                    }
                    mWork.postDelayed(this, 3000);
                }
            };
            mWork.post(mNavGuardTick);
        }
        log("仪表导航区接管已启动（3 秒一次，总线开高德模式）");
    }

    private void stopNavGuard() {
        synchronized (mNavGuardLock) {
            if (mNavGuardTick != null) {
                mWork.removeCallbacks(mNavGuardTick);
                mNavGuardTick = null;
            }
        }
    }

    // ---------- 总线抓包 ----------

    private volatile boolean mSniffing = false;

    /** 同一个事件内容 5 秒内只留一条，否则进度类事件一秒钟就能把日志刷爆。 */
    private final HashMap<String, Long> mSniffSeen = new HashMap<>();

    public boolean sniffing() { return mSniffing; }

    /**
     * 打开后把车机自己在发的那批事件原样倒进日志，并另存一份完整文本。
     * 这套协议没有文档，之前全靠猜，猜错就是静默失败 —— 有真实数据才能一次对接对。
     * 界面日志只有 8000 字，会被高频事件冲掉，所以落一份文件好取。
     */
    public String startSniff() {
        Vd v = Vd.connect(this);
        if (!v.ok()) return "总线没连上，抓不了（" + v.bindReport + "）";
        String err = v.sniff(Vd.SNIFF_IDS, s -> sniffLine(s));
        if (err == null) {
            mSniffing = true;
            log("总线抓包已开启，去放首歌 / 发起导航看看");
        }
        return err;
    }

    private void sniffLine(String s) {
        long now = System.currentTimeMillis();
        int sp = s.indexOf(' ');
        final String key = sp <= 0 ? s : s.substring(0, sp);
        boolean hit;
        synchronized (mSniffSeen) {
            Long t = mSniffSeen.get(key);
            if (now - (t == null ? 0L : t) < 5000L) hit = true;
            else {
                if (mSniffSeen.size() > 200) mSniffSeen.clear();
                mSniffSeen.put(key, now);
                hit = false;
            }
        }
        if (hit) return;
        log("抓到 " + s);
        try {
            File f = new File(getExternalFilesDir(null), "sniff.txt");
            if (f.length() > 3_000_000L) f.delete();
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(new SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new Date()) + "  " + s + "\n");
            w.close();
        } catch (Throwable t) {
            Log.w(TAG, "sniff file failed: " + t);
        }
    }

    public String stopSniff() {
        mSniffing = false;
        synchronized (mSniffSeen) { mSniffSeen.clear(); }
        Vd v = Vd.inst();
        String e = v != null ? v.stopSniff() : null;
        log("总线抓包已关闭，记录存在 sniff.txt");
        return e;
    }

    /**
     * 只用来收拾老版本留下的烂摊子：v4.2.x 会在导航档禁用 psmap 抢通道，
     * 结果把它的总线订阅打断（重启后必须再禁一次才显示）。现在这条路已废弃，
     * 开机和退出投屏时若发现还是我们禁的状态就恢复回去，之后再也不同它。
     */
    private void restoreAmap() {
        if (!mAmapDisabledByUs && !mCfg.amapOffByUs()) return;
        mAmapDisabledByUs = false;
        mCfg.setAmapOffByUs(false);
        String err = Caster.setAmapEnabled(this, true);
        log(err == null ? "已恢复原车高德" : "恢复高德失败：" + err);
    }

    /** 三指右滑：投着的退回主屏，收掉我们的页面，仪表退回原显示模式。 */
    void exitFromGesture() {
        retireCurrent();
        ClusterActivity.closeAll();
        MirrorActivity.closeAll();
        dockRestore();
        restoreTheme();
        restoreAmap();
        log("已退出投屏，仪表退回原显示模式");
    }

    // ---------- 沉浸 dock ----------

    /**
     * 投屏期间收起 systemui 那排 dock（写原车自己的 com.desaysv.status.bar.status=0）。
     * 只在「本进程这次投屏还没记过原值」时才写日志说明，换投 B 不会重复刷屏。
     * 开关关着、或没授权写不了，都照实说一条就不管了。
     */
    private boolean mDockLogged = false;

    private void dockHide() {
        if (!mCfg.dockImmersive()) return;
        if (!mDockLogged) { mDockLogged = true; log(Dock.hide(this)); }
    }

    private void dockRestore() {
        mDockLogged = false;
        String r = Dock.restore(this);
        if (r == null) return;
        log("dock 还原失败：" + r);
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

    // ---------- 车身控制 / 记录仪（这轮的主体） ----------

    /**
     * 总线车控：车窗（162~165 连发）、尾门（写 92、读 327684/162 开度）、
     * 后视镜（201/218 位置读取+写入）、档位。
     * 服务持有，页面只是它的另一个视图 —— 关页面不影响挂 R 自动下翻。
     */
    CarCtl carCtl;

    /**
     * 四路环视摄像头。这台车 DVR 子系统不存在（isDVRExist==0、moduleId 327700
     * 一次都没抓到），所以「录像」只能我们自己用 Camera2 + GLES 拼接 + MediaCodec 做。
     */
    CamCtl cams;

    /** 上次看到的档位，用来抓「进 R / 退 R」这两个沿。 */
    private int mGear = 0;
    private boolean mDipActive = false;
    private boolean mGearLogged = false;

    /** 服务级记录仪状态：界面用 cams.isRecording() 直读，这里不再缓存假值。 */
    public boolean dvrRecording() { return cams.isRecording(); }

    /** 紧急锁定开关（加速度计）：@return null=成功，否则真实原因（这台机没有该传感器）。 */
    public String dvrEmergency(boolean on) { return cams.setEmergency(on); }

    /** 记录仪：开始四路同录 / 停止 / 拍照 / 四宫格。 */
    public void dvrStart() { cams.recordAll(); }
    public void dvrStop() { cams.stopRecording(); }
    public void dvrSnapshot(CamView box, CamCtl.StrCb cb) { cams.snapshot(box, cb); }
    public void dvrQuad(CarCtl.LogCallback cb) { cams.snapshotQuad(cb); }
    public void dvrLock(CarCtl.LogCallback cb) { cams.lockCurrent(cb); }
    public String dvrSegment() { return cams.segmentInfo(); }
    public String dvrCamIds() { return cams.cameraIds(); }

    /**
     * 谁在用摄像头这件事必须记账：倒车页那三格每次刷新都会重建视图，
     * 重建一次就 attach 一轮，镜头会被反复抢占 —— 这正是「摄像头老被关掉」
     * 的人为成因之一。epoch 只在真正接/摘线时自增，界面据此决定要不要重建。
     */
    private volatile int mCamEpoch = 0;

    public int camEpoch() { return mCamEpoch; }

    public void camsAttach(CamView box, String id, String name) {
        mCamEpoch++;
        cams.attach(box, id, name);
    }

    public void camsDetach(String[] ids) { mCamEpoch++; cams.detach(ids); }
    public void camsDetachAllExcept(String[] keep) { mCamEpoch++; cams.detachAllExcept(keep); }
    public CamCtl cams() { return cams; }

    /** 尾门/车窗/后视镜读取与下发：界面直接调，结果回在日志里。 */
    public CarCtl car() { return carCtl; }

    /** 叫原车把 360 弹出来（能弹算赚到，弹不动也不影响我们自己的四路画面）。 */
    public void avmAction(final boolean open) {
        mWork.post(new Runnable() {
            @Override public void run() {
                Vd v = Vd.connect(CastService.this);
                if (!v.ok()) {
                    log("总线没连上，360 开不了（" + v.bindReport + "）");
                    return;
                }
                String e = v.openAvm(open);
                log(e == null
                        ? (open ? "已请原车打开 360（渲染在 QNX 侧，Android 抓不到它的画面）"
                                : "已请原车关闭 360")
                        : "原车 360 没响应：" + e);
            }
        });
    }

    // ---------- 挂 R 自动下翻：读用户保存好的镜位，两边同时写 ----------

    private final CarCtl.ResultCallback mNoop = new CarCtl.ResultCallback() {
        @Override public void accept(boolean ok, String message) { }
    };

    /**
     * 每秒读一次 327684/26（1P 2R 3N 4D）。抓到「进 R」就把保存好的「下翻位」同时写给
     * 201/218（左右一对，等效人工同时按）；「退 R」再写回保存好的「正常位」。
     * 取证：201/218 在这台车的总线上读得到真实值（log:108380/108385 values=[1]），
     * 之前 209/210 方向值那套标定方案实机无效，已整体删除。
     * 没保存过对应位置就照实写日志，绝不空发指令装作动了。
     */
    private void watchGear() {
        Vd v = Vd.inst();
        if (v == null || !v.ok()) return;
        int[] ga = v.get(CarCtl.EV_STATE, CarCtl.CMD_GEAR);
        int g = ga != null && ga.length > 0 ? ga[0] : 0;
        if (g == mGear) return;
        int prev = mGear;
        mGear = g;
        if (!mGearLogged && g > 0) {
            mGearLogged = true;
            log("档位总线已读到：" + CarCtl.gearName(g));
        }
        // 挡位自动录（盯盯车 auto_record_on_r_gear / d_gear 同款自写逻辑）：
        // 换挡沿上挂入 R/D 自动开录、挂入 P 自动收尾。手动停录后，下一次 R/D
        // 换挡沿它还会再开 —— 这是它的设计行为，按钮状态每 1.5 秒跟着真实走。
        if (mCfg.dvrAutoGear()) {
            if ((g == 2 || g == 4) && prev != g && !cams.isRecording()) {
                log("挂入 " + CarCtl.gearName(g) + "，挡位自动录：开录");
                cams.recordAll();
            } else if (g == 1 && prev != 1 && cams.isRecording()) {
                log("挂入 P，挡位自动录：收尾");
                cams.stopRecording();
            }
        }
        if (!mCfg.mirrorDip()) return;
        if (g == 2 && prev != 2) {
            mDipActive = true;
            if (!mCfg.mirrorReady()) {
                log("挂 R 了，但下翻位还没保存：先在倒车页把镜调好→读取→保存");
            } else {
                log("挂入 R，左右镜同时写下翻位（左=" + mCfg.mirrorDownL()
                        + " 右=" + mCfg.mirrorDownR() + "）");
                carCtl.writeMirrorPos(mCfg.mirrorDownL(), mCfg.mirrorDownR(), mNoop);
            }
        } else if (g != 2 && prev == 2) {
            mDipActive = false;
            if (!mCfg.mirrorNormalReady()) {
                log("已退 R，但正常位没保存，镜位不动");
            } else {
                log("退出 R，左右镜同时写回正常位（左=" + mCfg.mirrorNormalL()
                        + " 右=" + mCfg.mirrorNormalR() + "）");
                carCtl.writeMirrorPos(mCfg.mirrorNormalL(), mCfg.mirrorNormalR(), mNoop);
            }
        }
    }

    /** 界面打开时读一次当前档位，免得倒车页一进来什么都不显示。 */
    public void gearNow(CarCtl.IntCallback cb) { carCtl.readGear(cb); }

    /**
     * 极简模式的旧做法（自动挂 ClusterOverlay 悬浮层）已删除。
     * 取证（ClusterCast-投屏取证-v4.6.2.md）：仪表原车画面在 display 2 的最高层
     * （QNX 合成 / systemui system window，mMaxWindowLayer 限制 log:57036），
     * TYPE_APPLICATION_OVERLAY 越不过去 —— 实机现象就是「极简模式还是原车显示状态，
     * 没有画成功」。现在极简档投屏改走已证明可行的一条路：
     * 我们自己那个 Activity 直接启动在 display 2（log:38137/38196 抓到了 ClusterActivity
     * 跑在 display 2 上），成为该屏 top activity 时就能盖住原车画面；做不到也不谎报。
     *
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

    // ---------- 档位巡检 / 系统弹窗顶回 ----------

    /** 静态嵌套 + 弱引用：服务销毁后回环自动停。 */
    static class GearTick implements Runnable {
        private final WeakReference<CastService> ref;
        int ticks = 0;
        GearTick(CastService s) { ref = new WeakReference<>(s); }
        @Override public void run() {
            CastService s = ref.get();
            if (s == null) return;
            ticks++;
            // 每拍 1 秒：挂 R 下翻要快，档位每拍读；弹窗压制两拍一次（它 20 秒来一次）。
            s.watchGear();
            if (ticks % 2 == 0) s.guardTick();
            s.mWork.postDelayed(this, 1000);
        }
    }

    private void guardTick() {
        if (!mCfg.guard()) return;
        String r = AdminGuard.tick(this);
        if (r == null) return;
        log(r);
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
    /** 息屏/亮屏联动录像的延时（EVCam SCREEN_OFF/ON_DELAY_MS 同款 10 秒）。 */
    static final long SCREEN_OFF_DELAY_MS = 10_000L;
    static final long SCREEN_ON_DELAY_MS = 10_000L;

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
