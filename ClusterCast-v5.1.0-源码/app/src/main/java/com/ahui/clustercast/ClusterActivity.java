package com.ahui.clustercast;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 仪表屏（display 2）专用极简页：只显示"正在放什么歌"，不搬任何第三方 App 的界面。
 * 音乐 App 那份实例留在主屏，所以点它照常打开、投屏互不影响。
 */
public class ClusterActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private Tick tick;

    private FrameLayout root;
    private ImageView cover;
    private TextView title;
    private TextView artist;
    private TextView tvPos;
    private TextView tvDur;
    private TextView line;
    private View fill;
    private View rest;
    private String lastKey = "";
    private boolean mMeasured = false;
    private long mLostAt = 0L;
    private int mRelaunch = 0;
    private boolean mGaveUpLogged = false;

    /**
     * 丢焦点后的复查：还在被别人压着就重新拉回仪表屏（带冷却和次数上限）。
     * 必须是内部类而不是 lambda —— lambda 里的 this 指向 Activity，
     * postDelayed(this, …) 会编译成"把 Activity 当 Runnable 传"。
     */
    private final Runnable topGuard = new Runnable() {
        @Override public void run() {
            CastService s = CastService.inst();
            if (s == null) return;
            // 已经不在投屏期（右滑退出、换投别的）就不管了，那是原车画面该回来的时候
            if (!getPackageName().equals(s.mCastPkg)) return;
            if (isFinishing() || hasWindowFocus()) return;
            if (!new Cfg(ClusterActivity.this).forceTop()) {
                s.log("仪表页被压到下面了（强制顶层没开，不重拉）");
                return;
            }
            if (System.currentTimeMillis() - mLostAt < 1200) {
                h.postDelayed(this, 1200); return;
            }
            if (mRelaunch >= 5) {
                if (!mGaveUpLogged) {
                    mGaveUpLogged = true;
                    s.log("重拉 5 次仍压不住 display 2 上的那个窗口 —— "
                            + "这一层我们在 Android 侧拿不到，照实认输");
                }
                return;
            }
            mRelaunch++;
            s.log("仪表页被压住了，第 " + mRelaunch + " 次强制拉回顶层");
            String e = Caster.startOnDisplay(ClusterActivity.this, getPackageName(),
                    ClusterActivity.class.getName(), Caster.CLUSTER, true);
            if (e != null) s.log("重拉失败：" + e);
            h.postDelayed(this, 8000);
        }
    };

    /** 静态嵌套 + 弱引用：服务销毁后回环自动停。 */
    private static class Tick implements Runnable {
        private final WeakReference<ClusterActivity> ref;
        Tick(ClusterActivity a) { ref = new WeakReference<>(a); }
        @Override public void run() {
            ClusterActivity a = ref.get();
            if (a == null) return;
            a.refresh();
            a.h.postDelayed(this, 400);
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 全屏铺满仪表屏：隐藏系统栏，内容延伸到显示屏物理边缘，
        // 不被仪表 overlay 挤成"上半屏"。
        // FLAG_LAYOUT_NO_LIMITS 是关键一条：没有它，Activity 投到 display 2 只会被
        // 原车划在上半区（实机量到的就是半屏），加了才允许画到物理整屏。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams at = getWindow().getAttributes();
            at.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(at);
        }
        setContentView(build());
        synchronized (ALIVE) { ALIVE.add(this); }
        tick = new Tick(this);
        h.post(tick);
    }

    @Override protected void onDestroy() {
        h.removeCallbacks(tick);
        synchronized (ALIVE) { ALIVE.remove(this); }
        super.onDestroy();
    }

    /**
     * 铺满没有是量出来的，不是嘴上说的：拿到焦点后把自己的实际绘制尺寸
     * 和仪表屏物理尺寸对一次，不一致就把差值写进日志（多半是这台 ROM 仍按半屏布局）。
     *
     * 丢焦点 = display 2 上别的东西压上来了 —— 「强制顶层」就是从这接管：
     * 只要还在投屏期且开关没关，延迟 1.2 秒复查一次，还没焦点就把本页面重新
     * 拉回 display 2（singleInstance，不会叠任务）。带 8 秒冷却和 5 次上限，
     * 压不动就照实写日志承认输，绝不无限重拉把仪表刷屏。
     */
    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) {
            h.removeCallbacks(topGuard);
            if (mLostAt != 0L && svc() != null) svc().log("仪表页焦点已抢回");
            mLostAt = 0L;
        } else {
            mLostAt = System.currentTimeMillis();
            h.removeCallbacks(topGuard);
            h.postDelayed(topGuard, 1200);
        }
        if (!has || mMeasured) return;
        mMeasured = true;
        root.post(new Runnable() {
            @Override public void run() {
                android.graphics.Rect b = Caster.fullBounds(ClusterActivity.this, Caster.CLUSTER);
                int w = root.getWidth(), ht = root.getHeight();
                final CastService s = svc();
                if (s == null) return;
                if (b == null) s.log("仪表页已铺出 " + w + "x" + ht
                        + "（读不到仪表屏物理尺寸，无法判定）");
                else if (w == b.width() && ht == b.height())
                    s.log("仪表页已铺满整屏 " + w + "x" + ht);
                else s.log("仪表页只占 " + w + "x" + ht + "，仪表屏物理是 "
                        + b.width() + "x" + b.height() + " —— 这台 ROM 仍按半屏布局");
            }
        });
    }

    private CastService svc() { return CastService.inst(); }

    private View build() {
        root = new FrameLayout(this);
        // 仪表页保持纯黑底：极简模式下这页就是背景，不能透出别的颜色
        root.setBackgroundColor(Color.BLACK);

        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        add(root, cover, dp(200), dp(200), Gravity.START | Gravity.CENTER_VERTICAL,
                dp(56), 0, 0, 0);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        lp.leftMargin = dp(200) + dp(84);
        lp.rightMargin = dp(56);
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        root.addView(col, lp);

        title = Ui.text(this, 44, Color.WHITE, Typeface.BOLD, 2);
        col.addView(title, Ui.lw());

        artist = Ui.text(this, 26, 0xFFB9C2CC, Typeface.NORMAL, 1);
        LinearLayout.LayoutParams alp = Ui.lw();
        alp.topMargin = dp(10);
        col.addView(artist, alp);

        // 车机字体没有 █░ 这类方块字形，进度条只能用真 View 画
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        tvPos = Ui.text(this, 20, GREEN, Typeface.NORMAL, 1);
        row.addView(tvPos, Ui.ww());
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackgroundColor(0xFF2A2A2A);  // 仪表页始终是黑底，用深色槽
        fill = new View(this);
        fill.setBackgroundColor(GREEN);
        rest = new View(this);
        track.addView(fill, Ui.weighted(1f, dp(8)));
        track.addView(rest, Ui.weighted(0f, dp(8)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(16);
        tlp.rightMargin = dp(16);
        row.addView(track, tlp);
        tvDur = Ui.text(this, 20, GREEN, Typeface.NORMAL, 1);
        row.addView(tvDur, Ui.ww());
        LinearLayout.LayoutParams rlp = Ui.lw();
        rlp.topMargin = dp(26);
        col.addView(row, rlp);

        line = Ui.text(this, 18, 0xFF7A838C, Typeface.NORMAL, 1);
        LinearLayout.LayoutParams llp = Ui.lw();
        llp.topMargin = dp(10);
        col.addView(line, llp);
        return root;
    }

    private void add(FrameLayout parent, View v, int w, int hgt, int gravity,
                     int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, hgt);
        lp.gravity = gravity;
        lp.setMargins(l, t, r, b);
        parent.addView(v, lp);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void refresh() {
        MediaController mc = MusicListener.inst() != null ? MusicListener.inst().active() : null;
        MediaMetadata md = MusicListener.meta(mc);

        if (mc == null || md == null) {
            String tip = MusicListener.ready() ? "没有正在播放的音乐"
                    : "未获得通知使用权，读不到播放信息";
            show(tip, "", 0, 0, null, null);
            return;
        }

        String name = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        String who = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (who == null) who = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
        String src = label(mc.getPackageName());
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);

        long pos = MusicListener.position(mc);
        long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String key = src + "|" + name + "|" + who + "|" + (pos / 1000);
        if (key.equals(lastKey)) return;
        lastKey = key;
        show(name == null ? "未知歌曲" : name, who == null ? "" : who, pos, dur, src, art);
    }

    private void show(String t, String a, long pos, long dur, String src, Bitmap art) {
        title.setText(t);
        artist.setText(a);
        boolean hasBar = dur > 0;
        tvPos.setVisibility(hasBar ? View.VISIBLE : View.GONE);
        tvDur.setVisibility(hasBar ? View.VISIBLE : View.GONE);
        ((LinearLayout.LayoutParams) fill.getLayoutParams()).weight =
                hasBar ? (float) pos : 0f;
        ((LinearLayout.LayoutParams) rest.getLayoutParams()).weight =
                hasBar ? Math.max(0f, (float) (dur - pos)) : 1f;
        fill.requestLayout();
        tvPos.setText(hasBar ? fmt(pos) : "");
        tvDur.setText(hasBar ? fmt(dur) : "");
        line.setText(src == null ? "" : "来源：" + src);
        if (art != null) cover.setImageBitmap(art); else cover.setImageDrawable(null);
    }

    /** 包名换成应用名，仪表屏上不用露 com.xxx。 */
    private String label(String pkg) {
        if (pkg == null) return null;
        try {
            android.content.pm.ApplicationInfo ai =
                    getPackageManager().getApplicationInfo(pkg, 0);
            return getPackageManager().getApplicationLabel(ai).toString();
        } catch (Throwable t) { return pkg; }
    }

    private String fmt(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private static final int GREEN = 0xFF61C96B;
    private static final ArrayList<ClusterActivity> ALIVE = new ArrayList<>();

    /** 三指右滑退出时由服务调用：把这些实例收掉，仪表屏还给原车画面。 */
    static void closeAll() {
        List<ClusterActivity> copy;
        synchronized (ALIVE) { copy = new ArrayList<>(ALIVE); ALIVE.clear(); }
        for (Activity a : copy) { try { a.finish(); } catch (Throwable ignored) { } }
    }
}
