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
import android.content.pm.ApplicationInfo;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 常驻前台服务：接收车机系统自己发出的三指手势广播。
 *
 * 该广播由 system_server 里的 com.android.server.wm.CarSystemGesturesManager
 * 用 sendBroadcastAsUser(intent, UserHandle.ALL) 发出，不带任何接收权限：
 *   action  = android.intent.action.car_system_gesture_mode
 *   extras  = gesture(int), display(int), MotionEvent
 *   gesture 编码 = 手指数*100 + 方向（0左 1右 2下 3上 4捏合）
 * 所以 300 = 三指左滑，301 = 三指右滑。普通应用注册个接收器就能收到，
 * 不需要 root、不需要 Shizuku、也不需要开无障碍。
 */
public class CastService extends Service {

    public static final String TAG = "ClusterCast";
    public static final String GESTURE_ACTION = "android.intent.action.car_system_gesture_mode";
    public static final int G_3_LEFT = 300;
    public static final int G_3_RIGHT = 301;
    private static final long DEBOUNCE_MS = 800;

    private static CastService sInst;
    public static CastService inst() { return sInst; }

    public static void start(Context c) {
        Intent i = new Intent(c, CastService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    Handler mHandler;
    /** VDBus 的 bindService/getOnce 是同步 binder，绝不能在主线程跑。 */
    Handler mWork;
    Cfg mCfg;
    long mLastGestureAt;
    private LogSink mSink;
    private final StringBuilder mLogBuf = new StringBuilder();
    private final SimpleDateFormat mTime = new SimpleDateFormat("HH:mm:ss");

    public interface LogSink { void onLog(String s); }

    /** 静态嵌套类：匿名内部类会让本项目的 d8 崩溃。 */
    public static class GestureRx extends BroadcastReceiver {
        final CastService s;
        GestureRx(CastService s) { this.s = s; }

        @Override public void onReceive(Context context, Intent intent) {
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

    private final Runnable mCast = () -> castFromGesture();
    private final Runnable mExit = () -> exitFromGesture();

    /** 给界面上的按钮用：动作一律丢到工作线程，总线调用不能卡主线程。 */
    public void castNow() { mWork.post(mCast); }
    public void exitNow() { mWork.post(mExit); }

    @Override public void onCreate() {
        super.onCreate();
        sInst = this;
        mHandler = new Handler(getMainLooper());
        HandlerThread ht = new HandlerThread("cast-work");
        ht.start();
        mWork = new Handler(ht.getLooper());
        mCfg = new Cfg(this);
        startForegroundNotice();
        registerReceiver(new GestureRx(this), new IntentFilter(GESTURE_ACTION));
        mWork.post(mMirror = new MirrorTick(this));
        mWork.post(() -> {
            Vd v = Vd.connect(this);
            if (!v.ok()) { log("车身总线连不上，投屏仍可用"); return; }
            log("已连上车身总线");
            mWork.postDelayed(mThemeRetry, 1500);
        });
        log("服务已启动，正在监听三指手势广播");
    }

    private int mThemeTries;

    /** 总线刚绑定时读不到主题，隔 1.5 秒再试，最多 5 次。 */
    private final Runnable mThemeRetry = () -> {
        Vd v = Vd.inst();
        if (v == null) return;
        int t = v.getTheme();
        if (t >= 0) { mThemeSeen = t; log("当前仪表模式：" + Vd.themeName(t)); return; }
        if (++mThemeTries < 5) mWork.postDelayed(this.mThemeRetry, 1500);
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotice();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        sInst = null;
        super.onDestroy();
    }

    // ---------- 动作 ----------

    /** 进投屏前的仪表主题，退出时要还回去；-1 表示当前不在投屏中。 */
    int mThemeBefore = -1;

    /**
     * 三指左滑：只改仪表通道，不碰主屏。
     * 先把仪表切到原车「极简模式」（这一档不占画面，第三方 Activity 能盖上去），
     * 再把我们的页面放到仪表屏。之后主屏点任何应用都不会再影响仪表，只有再次三指滑动才切。
     */
    void castFromGesture() {
        enterSimpleTheme();
        if (mCfg.simple()) { castSimple(); return; }
        String[] t = target();
        if (t == null) { log("还没选投屏应用，先打开「仪表投屏」选一个"); return; }
        if (Caster.startOnDisplay(this, t[0], t[1], Caster.CLUSTER)) {
            log("已投屏 " + label(t[0]));
        } else {
            log("投屏失败 " + label(t[0]));
        }
    }

    /** 极简模式：仪表屏只放我们自己的播放页，音乐 App 那份实例完全不动，主屏照常点。 */
    void castSimple() {
        if (Caster.startOnDisplay(this, getPackageName(), ClusterActivity.class.getName(),
                Caster.CLUSTER)) {
            log("仪表极简页已投出" + (MusicListener.ready() ? "" : "（还没给通知使用权，读不到歌名）"));
        } else {
            log("极简页启动失败");
        }
    }

    /** 三指右滑：收掉我们的页面，仪表退回用户原本设置的显示模式。不碰原车高德。 */
    void exitFromGesture() {
        ClusterActivity.closeAll();
        restoreTheme();
        log("已退出投屏，仪表退回原显示模式");
    }

    private void enterSimpleTheme() {
        Vd v = Vd.connect(this);
        if (!v.ok()) { log("总线没连上，仪表模式不变，直接把页面放上去"); return; }
        if (mThemeBefore < 0) mThemeBefore = readTheme(v);
        v.setTheme(Vd.THEME_SIMPLE);
        int back = readBack(v);
        mThemeSeen = back;
        if (back == Vd.THEME_SIMPLE) {
            log("仪表已切到极简模式（原=" + Vd.themeName(mThemeBefore) + "）");
        } else {
            log("写极简模式没生效，仪表还停在" + Vd.themeName(back) + "，页面照样放上去");
        }
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

    /** 原车下发后也是延迟回读的，总线要一点时间才落到 MCU。 */
    private static int readBack(Vd v) {
        try { Thread.sleep(250); } catch (InterruptedException ignored) { }
        int t = v.getTheme();
        return t < 0 ? v.getTheme() : t;
    }

    private int readTheme(Vd v) {
        int t = v.getTheme();
        mThemeSeen = t;
        return t;
    }

    /** 界面上显示的当前仪表主题，由工作线程刷新。 */
    volatile int mThemeSeen = -1;

    // ---------- 原车桌面音乐卡片对接 ----------

    /** 原车音乐自己会发总线，别抢它的活。 */
    private static final String ORIG_MEDIA = "com.desaysv.mediacenter";
    private MirrorTick mMirror;

    /** 静态嵌套 + 弱引用：本项目 d8 编不了捕获 this 的匿名内部类。 */
    private static class MirrorTick implements Runnable {
        final WeakReference<CastService> ref;
        String last = "";
        int ticks;
        MirrorTick(CastService s) { ref = new WeakReference<>(s); }

        @Override public void run() {
            CastService s = ref.get();
            if (s == null) return;
            ticks++;
            s.mirrorCard(this);
            s.mWork.postDelayed(this, 2000);
        }
    }

    void mirrorCard(MirrorTick t) {
        if (!mCfg.cardMirror()) return;
        MusicListener l = MusicListener.inst();
        if (l == null) { cardHint(t, "还没给通知使用权，读不到播放信息"); return; }
        MediaController c = l.active();
        MediaMetadata md = MusicListener.meta(c);
        if (md == null) { cardHint(t, "没读到带歌名的播放会话"); return; }
        if (c.getPackageName().equals(ORIG_MEDIA)) { t.last = ""; return; }
        String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) { cardHint(t, "会话有元数据但没有歌名"); return; }
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String album = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
        String key = c.getPackageName() + "|" + title + "|" + artist;
        if (key.equals(t.last)) return;
        t.last = key;
        Vd v = Vd.connect(this);
        if (!v.ok()) { cardHint(t, "车身总线连不上，推不上卡片"); return; }
        if (v.publishMedia(title, artist, album, null)) {
            if (!mCardLogged) {
                mCardLogged = true;
                log("桌面音乐卡片已对接：" + title + " - " + artist);
            }
        } else {
            cardHint(t, "总线已连上但推送失败，看 logcat 的 ClusterCast.Vd");
        }
    }

    /** 对接没成功时只提示一次，别每 2 秒刷一条。 */
    private void cardHint(MirrorTick t, String why) {
        if (t.ticks < 10 || mCardHinted) return;
        mCardHinted = true;
        log("桌面卡片未对接：" + why);
    }

    private boolean mCardHinted;

    private boolean mCardLogged;

    /** 优先跟随前台应用（需使用情况访问权限），没授权就用上次选定的目标。 */
    String[] target() {
        if (mCfg.followTop()) {
            String pkg = TopApp.get(this);
            if (pkg != null && !pkg.equals(getPackageName())) {
                String cls = Caster.launchable(this, pkg);
                if (cls != null) return new String[] { pkg, cls };
            }
        }
        String pkg = mCfg.pkg(), cls = mCfg.cls();
        if (pkg == null || cls == null) return null;
        return new String[] { pkg, cls };
    }

    private String label(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
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
        LogSink sink = mSink;
        Runnable r = () -> sink.onLog(mLogBuf.toString());
        if (sink != null) mHandler.post(r);
    }

    public String logText() { synchronized (mLogBuf) { return mLogBuf.toString(); } }

    private void startForegroundNotice() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String ch = "cast";
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                    new NotificationChannel(ch, "仪表投屏", NotificationManager.IMPORTANCE_MIN));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        b.setContentTitle("仪表投屏运行中")
                .setContentText("三指左滑投屏 · 三指右滑退出")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pi);
        startForeground(42, b.build());
    }
}
