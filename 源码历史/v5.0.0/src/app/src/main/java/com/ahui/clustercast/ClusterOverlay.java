package com.ahui.clustercast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Date;

/**
 * 仪表屏悬浮覆盖层：普通 Activity 投 display 2 只能落在原车划定的上半区，
 * 下半截还被原车界面盖着。悬浮窗层级高于应用窗口，铺到仪表屏物理全屏，
 * 用来显示正在播放的音乐（歌名/歌手/封面/进度）。
 *
 * 需要悬浮窗权限（Settings.canDrawOverlays）。车机系统应用一般默认有；
 * 没有时 addView 会抛异常，服务自己停并写日志，绝不闪退。
 */
public class ClusterOverlay extends Service {

    private WindowManager wm;
    private FrameLayout root = null;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Tick tick;

    private ImageView cover;
    private TextView title;
    private TextView artist;
    private TextView line;
    private TextView hudTime;
    private TextView hudSpeed;
    private TextView hudGear;
    private String lastKey = "";
    private boolean mHudDumped = false;
    private final long mHudStart = SystemClock.elapsedRealtime();
    private final java.text.SimpleDateFormat clock =
            new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);

    private static class Tick implements Runnable {
        private final WeakReference<ClusterOverlay> ref;
        Tick(ClusterOverlay s) { ref = new WeakReference<>(s); }
        @Override public void run() {
            ClusterOverlay s = ref.get();
            if (s == null) return;
            s.refresh();
            s.h.postDelayed(this, 800);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        sInst = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            build();
        } catch (Throwable t) {
            CastService cs = CastService.inst();
            if (cs != null)
                cs.log("仪表覆盖层起不来：" + t.getClass().getSimpleName()
                        + "（" + t.getMessage() + "）");
            stopSelf();
            return;
        }
        tick = new Tick(this);
        h.post(tick);
    }

    @Override public int onStartCommand(Intent i, int f, int id) {
        // 后台启动服务必须有通知兜底，否则 O 以上直接被拒/崩。
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                        new NotificationChannel("overlay", "仪表覆盖层",
                                NotificationManager.IMPORTANCE_MIN));
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "overlay")
                    : new Notification.Builder(this);
            b.setContentTitle("仪表覆盖层运行中")
                    .setSmallIcon(android.R.drawable.stat_sys_upload);
            startForeground(43, b.build());
        } catch (Throwable t) {
            CastService cs = CastService.inst();
            if (cs != null) cs.log("覆盖层通知起不来：" + t.getClass().getSimpleName());
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        h.removeCallbacks(tick);
        try { if (root != null) wm.removeView(root); } catch (Throwable ignored) { }
        root = null;
        if (sInst == this) sInst = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void build() {
        // 层级按用户要求改到【最顶层，强制的】：悬浮窗在窗口管理器里排在所有
        // 应用窗口之上（含 display 2 上的原车界面和我们自己的仪表页）。
        // v4.6.2 曾按「压不过原车画面」把整条路判死，那是把 QNX 合成的最底层
        // 和 Android 侧的窗口顺序混在一起说了 —— Android 侧这一层我们能排到最高，
        // 排没排到屏幕上见分晓，日志只报真实挂载结果。
        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        // 整块铺满，不留边、不躲原车表盘：车速/档位/油表由它自己盖在我们之上。
        // 若实机发现我们反而把表盘盖没了（说明我们层级更高），日志里量到的尺寸
        // 就是证据，那时改成我们自己把车速画在这一层上面。
        FrameLayout fl = new FrameLayout(this);
        // 极简档投屏：原车极简页留在底下，我们只把「投屏内容」画在它上面 ——
        // 底板必须透明，不透明就等于把人家那页换掉了（用户明确纠正过这一点）。
        fl.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(28), dp(20), dp(28), dp(20));

        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(200), dp(200));
        clp.rightMargin = dp(26);
        row.addView(cover, clp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams colp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(col, colp);

        title = Ui.text(this, 40, Color.WHITE, Typeface.BOLD, 2);
        artist = Ui.text(this, 24, 0xFFC6CED6, Typeface.NORMAL, 1);
        LinearLayout.LayoutParams alp = Ui.lw();
        alp.topMargin = dp(10);
        col.addView(title, Ui.lw());
        col.addView(artist, alp);
        line = Ui.text(this, 17, 0xFF8A939C, Typeface.NORMAL, 1);
        LinearLayout.LayoutParams llp = Ui.lw();
        llp.topMargin = dp(14);
        col.addView(line, llp);

        fl.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 原车那层仪表盘我们盖得住，所以由我们在投屏之上重写一遍：
        // 车速和档位是总线真值（VEHICLE_HAL 519/1024），时间是系统时间。
        // 没有真实来源的（水温、油量、里程）一律不画，绝不编个假的上去。
        LinearLayout hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.HORIZONTAL);
        hud.setGravity(Gravity.CENTER_VERTICAL);
        hud.setPadding(dp(30), dp(10), dp(30), dp(10));
        hudTime = Ui.text(this, 22, Color.WHITE, Typeface.BOLD, 1);
        hudGear = Ui.text(this, 22, Color.WHITE, Typeface.BOLD, 1);
        TextView big = Ui.text(this, 46, Color.WHITE, Typeface.BOLD, 1);
        big.setIncludeFontPadding(false);
        hudSpeed = big;
        TextView unit = Ui.text(this, 14, 0xFFC6CED6, Typeface.NORMAL, 1);
        unit.setText("km/h");
        hud.addView(hudTime, Ui.ww());
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.leftMargin = dp(26);
        hud.addView(hudGear, gp);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = dp(26);
        hud.addView(hudSpeed, sp);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        up.leftMargin = dp(6);
        hud.addView(unit, up);
        fl.addView(hud, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                // 不可触摸、不可聚焦、铺到物理屏、盖在应用之上
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28)
            p.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        // 关键：把这块悬浮窗挂到仪表屏。displayId 是 @hide 字段，公开 SDK 里没有，
        // 只能反射写；写不上就退回主屏并记日志，绝不假装投成功。
        if (!setDisplay(p, Caster.CLUSTER)) {
            throw new IllegalStateException("挂不到仪表屏（displayId 反射失败）");
        }
        wm.addView(fl, p);
        root = fl;
    }

    private boolean setDisplay(WindowManager.LayoutParams p, int id) {
        try {
            java.lang.reflect.Field f = WindowManager.LayoutParams.class
                    .getDeclaredField("displayId");
            f.setAccessible(true);
            f.setInt(p, id);
            return true;
        } catch (Throwable t) {
            CastService cs = CastService.inst();
            if (cs != null) cs.log("displayId 反射失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    private void refresh() {
        drawHud();
        MediaController c = MusicListener.inst() != null ? MusicListener.inst().active() : null;
        MediaMetadata md = MusicListener.meta(c);
        if (c == null || md == null) {
            show(MusicListener.ready() ? "没有正在播放的音乐" : "未获得通知使用权",
                    "", null, null);
            lastKey = "";
            return;
        }
        String name = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (name == null) name = "未知歌曲";
        String who = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (who == null) who = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        String src = label(c.getPackageName());
        String key = src + "|" + name + "|" + who;
        if (key.equals(lastKey)) return;
        lastKey = key;
        show(name, who == null ? "" : who, src, art);
    }

    /**
     * 顶栏这一排是我们自己画的：时间取系统时间，车速/档位取总线 VEHICLE_HAL 真值。
     * 拿不到真值就显示「--」，绝不填个看着像真的数字上去。
     * 第一次收到 519/1024 时把原样载荷写进日志，键名对不对一眼能核。
     */
    private void drawHud() {
        hudTime.setText(clock.format(new Date()));
        Vd v = Vd.inst();
        if (v == null) {
            hudSpeed.setText("--");
            hudGear.setText("--");
            return;
        }
        if (!mHudDumped) {
            String seen = v.hudSeen;
            // 刚起来总线可能还没发过来，等到有数据或 10 秒后再写这一条日志。
            if (seen != null && !seen.isEmpty()) {
                mHudDumped = true;
                CastService cs = CastService.inst();
                if (cs != null) cs.log("车速/档位原始载荷：" + seen);
            } else if (SystemClock.elapsedRealtime() - mHudStart > 10000L) {
                mHudDumped = true;
                CastService cs = CastService.inst();
                if (cs != null) cs.log("还没收到车速/档位事件（仪表总线上 519/1024 没发过来）");
            }
        }
        float s = v.hudSpeed;
        hudSpeed.setText(s < 0f ? "--"
                : String.format(java.util.Locale.US, "%.0f", s > 999f ? 999f : s));
        // 档位编码已在这台车的总线上钉死：327684 的 cmdId 26 = 1P 2R 3N 4D
        // （受控挂挡实测，和 AOSP GEAR_SELECTION 一致）。认不出的值直接写原数，绝不猜个 D 蒙人。
        int g = v.hudGear;
        String gl = CarCtl.gearLetter(g);
        hudGear.setText(gl != null ? gl : (g < 0 ? "--" : String.valueOf(g)));
    }

    private void show(String t, String a, String src, Bitmap art) {
        title.setText(t);
        artist.setText(a);
        line.setText(src == null ? "" : "来源：" + src);
        if (art != null) cover.setImageBitmap(art); else cover.setImageDrawable(null);
    }

    private String label(String pkg) {
        if (pkg == null) return null;
        try {
            android.content.pm.ApplicationInfo ai =
                    getPackageManager().getApplicationInfo(pkg, 0);
            return getPackageManager().getApplicationLabel(ai).toString();
        } catch (Throwable t) { return pkg; }
    }

    private static volatile ClusterOverlay sInst = null;

    static boolean alive() { return sInst != null; }

    /** 没给悬浮窗权限就不白跑一趟。 */
    static boolean canDraw(Context c) {
        try { return android.provider.Settings.canDrawOverlays(c); }
        catch (Throwable t) { return false; }
    }

    static void start(Context c) {
        // 不是前台服务：必须普通 startService，startForegroundService 不调
        // startForeground 会在 5 秒后崩。
        c.startService(new Intent(c, ClusterOverlay.class));
    }

    static void stop(Context c) { c.stopService(new Intent(c, ClusterOverlay.class)); }
}
