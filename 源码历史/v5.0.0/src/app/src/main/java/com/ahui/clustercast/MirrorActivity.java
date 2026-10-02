package com.ahui.clustercast;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 仪表屏（display 2）上的整屏镜像页：SurfaceView 直接吃 VirtualDisplay 的输出面，
 * 零拷贝，不经过我们自己的编码器。分辨率按仪表屏的真实物理尺寸现量
 * （开源那版把 1920x532 写死在常量里 —— 那是启源A06的屏，我们不照抄，
 * 我们量到多少写多少，铺没铺满以日志为准）。
 *
 * 注意语义：镜像 = 主屏此刻是什么仪表就显示什么，所以它和「单槽 B→B」契约
 * 天然不同 —— 必须用户自己在投屏页开「整屏镜像」开关才走这条路，
 * 默认仍是原来的「投当前前台应用那一份」。
 */
public class MirrorActivity extends Activity {

    private VirtualDisplay vd = null;
    private SurfaceView sv = null;
    private boolean measured = false;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable lost = new Runnable() {
        @Override public void run() {
            CastService s = CastService.inst();
            if (s == null) return;
            if (!getPackageName().equals(s.mCastPkg) || isFinishing()) return;
            if (hasWindowFocus()) return;
            s.log("镜像页被压住了（仪表屏上有我们拿不到的层）—— 退出重进或换回普通档");
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        SurfaceView s = new SurfaceView(this);
        s.setBackgroundColor(android.graphics.Color.BLACK);
        s.getHolder().setFormat(PixelFormat.OPAQUE);
        sv = s;
        setContentView(s);
        synchronized (ALIVE) { ALIVE.add(this); }
    }

    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) h.removeCallbacks(lost); else h.postDelayed(lost, 1500);
        if (!has || measured) return;
        measured = true;
        final CastService s = CastService.inst();
        if (s == null) return;
        final SurfaceView v = sv;
        if (v == null) return;
        v.post(new Runnable() {
            @Override public void run() {
                android.graphics.Rect b = Caster.fullBounds(MirrorActivity.this, Caster.CLUSTER);
                int w = v.getWidth(), ht = v.getHeight();
                if (b == null) s.log("镜像页已铺出 " + w + "x" + ht + "（读不到仪表屏物理尺寸）");
                else if (w == b.width() && ht == b.height())
                    s.log("镜像页已铺满整屏 " + w + "x" + ht);
                else s.log("镜像页只占 " + w + "x" + ht + "，仪表屏物理是 "
                        + b.width() + "x" + b.height() + " —— 这台 ROM 仍按半屏布局");
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        bindSurface();
    }

    private void bindSurface() {
        final SurfaceView s = sv;
        if (s == null) return;
        if (s.getHolder().getSurface().isValid()) { startVd(s); return; }
        s.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { startVd(s); }
            @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int ht) { }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { }
        });
    }

    private void startVd(SurfaceView s) {
        if (vd != null) return;
        final CastService svc = CastService.inst();
        MediaProjection mp = MirrorTok.ensure(this);
        if (mp == null) {
            if (svc != null)
                svc.log("镜像起不来：系统录屏授权没了或 MediaProjection 拿不到");
            finish(); return;
        }
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        android.view.Display target;
        try { target = dm.getDisplay(Caster.CLUSTER); } catch (Throwable t) { target = null; }
        if (target == null) {
            if (svc != null) svc.log("镜像起不来：找不到仪表屏（display " + Caster.CLUSTER + "）");
            finish(); return;
        }
        DisplayMetrics m = new DisplayMetrics();
        target.getRealMetrics(m);
        if (m.widthPixels <= 0 || m.heightPixels <= 0) {
            if (svc != null) svc.log("镜像起不来：仪表屏报的尺寸是 0");
            finish(); return;
        }
        try {
            // 截的是主屏（default display），输出到仪表屏上的这块面；
            // 尺寸直接给仪表屏物理像素，拉伸交给系统，不自己缩放。
            vd = mp.createVirtualDisplay("dvr-mirror",
                    m.widthPixels, m.heightPixels, m.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    s.getHolder().getSurface(), null, new Handler(Looper.getMainLooper()));
            if (svc != null)
                svc.log("镜像已开：主屏整屏 -> 仪表屏 "
                        + m.widthPixels + "x" + m.heightPixels + "（Surface 直通，零拷贝）");
        } catch (Throwable t) {
            if (svc != null)
                svc.log("镜像没起来：" + t.getClass().getSimpleName() + " " + t.getMessage());
            finish();
        }
    }

    @Override protected void onDestroy() {
        h.removeCallbacks(lost);
        try { if (vd != null) vd.release(); } catch (Throwable t) { }
        vd = null;
        synchronized (ALIVE) { ALIVE.remove(this); }
        super.onDestroy();
    }

    private static final ArrayList<MirrorActivity> ALIVE = new ArrayList<>();

    static boolean isAlive() {
        synchronized (ALIVE) { return !ALIVE.isEmpty(); }
    }

    /** 退出投屏/换档时由服务调用：收掉镜像页（VirtualDisplay 随页面释放）。 */
    static void closeAll() {
        List<MirrorActivity> copy;
        synchronized (ALIVE) { copy = new ArrayList<>(ALIVE); ALIVE.clear(); }
        for (Activity a : copy) { try { a.finish(); } catch (Throwable ignored) { } }
    }
}
