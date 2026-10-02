package com.jietu.clustercast;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * v14 主路径：仪表屏「悬浮」投屏。
 *
 * 窗口机制抄自 amap-companion 的 OverlayService（ensureClusterMirror 614-735 /
 * findClusterDisplay 794-823）：createDisplayContext(仪表屏) 拿到仪表屏自己的
 * WindowManager，往上 addView 一个 TYPE_APPLICATION_OVERLAY 全屏层。同一层级的
 * 窗口后加者在上面，所以这层必然浮在原车高德的全屏 overlay 之上 ——
 * 原车高德不用禁、仪表主题不用切、总线一概不碰，仪表原样显示。
 *
 * 悬浮层里放一个 SurfaceView，绑定一块和仪表同分辨率的公共 VirtualDisplay；
 * 自选 app 用 ActivityOptions.setLaunchDisplayId(虚拟屏) 启进去 —— app 渲染在
 * 自己的虚拟屏里（等于在后台跑），主屏随便用，这就是「后台悬浮」，不是录屏。
 *
 * 失败兜底：缺悬浮窗权限、找不到仪表屏、系统拒绝往虚拟屏启 Activity 时返回
 * 真实错误，CastService 自动退回 v13 搬屏路径。
 */
public class ClusterOverlay {

    /** 投屏结果回调：ok=true 投上了；ok=false err 是真实原因（界面日志照抄）。 */
    public interface Callback { void onResult(boolean ok, String err); }

    private static final String TAG = "ClusterCast.Overlay";
    private static final String VD_NAME = "ClusterCastVD";
    /** 仪表物理分辨率兜底值（这台车 display 2 是 1920x720）；实际以 getRealMetrics 为准。 */
    private static final int VD_W = 1920, VD_H = 720, VD_DPI = 160;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());

    private int vdW = VD_W, vdH = VD_H, vdDpi = VD_DPI;
    private FrameLayout panel;
    private SurfaceView surface;
    private WindowManager clusterWm;
    private VirtualDisplay vd;
    private int vdId = -1;

    /** 虚拟屏里当前投着的包（null=没有）；悬浮层重建后靠它自动重投。 */
    private volatile String castPkg = null;

    /** cast() 受理后还没落到虚拟屏的请求（等 surface 起来）。全在主线程读写。 */
    private String pendingPkg = null, pendingCls = null;
    private Callback pendingCb = null;

    public ClusterOverlay(Context appCtx) {
        app = appCtx.getApplicationContext();
    }

    /** 当前悬浮层是否活着（活着=已投过至少一次，再投就是往里换 app）。 */
    public boolean active() { return panel != null; }

    public String castPkg() { return castPkg; }

    // ---------- 预检 / 找屏 ----------

    /**
     * 开投前的静态检查。返回 null=可以尝试；否则是给界面日志的错误描述。
     * 悬浮窗权限查不到就不拦（让 addView 自己去撞，错误照实上报）。
     */
    public static String precheck(Context c) {
        try {
            if (!Settings.canDrawOverlays(c)) return "没有「显示在其他应用上层」权限";
        } catch (Throwable ignored) { }
        return findClusterDisplay(c) == null ? "没找到仪表屏" : null;
    }

    /** 优先 2 号屏（这台车机仪表屏固定是 display 2），再找 presentation 屏，最后任意非默认屏。 */
    static Display findClusterDisplay(Context c) {
        try {
            DisplayManager dm =
                    (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            Display[] all = dm.getDisplays();
            for (Display d : all)
                if (d.getDisplayId() == 2) return d;
            Display[] pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            if (pres.length > 0) return pres[0];
            for (Display d : all)
                if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        } catch (Throwable t) {
            Log.w(TAG, "findClusterDisplay failed: " + t);
        }
        return null;
    }

    /** 跳悬浮窗授权页；打不开返回 false（车机 ROM 常没有这页面，用 adb appops 授权）。 */
    public static boolean requestPermission(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- 投屏入口 ----------

    /**
     * 把自选 app 投进仪表悬浮层。异步：结果经 cb 回来（cb 在主线程被调）。
     * 悬浮层没起就先起（等 surface），起了就直接把 app 启进虚拟屏 ——
     * 已投着 A 再投 B，B 直接盖上去，A 留在虚拟屏后台。
     */
    public void cast(String pkg, String cls, Callback cb) {
        main.post(new Runnable() {
            @Override public void run() { castOnMain(pkg, cls, cb); }
        });
    }

    private void castOnMain(String pkg, String cls, Callback cb) {
        try {
            if (panel != null && vd == null) {
                // 悬浮层在但虚拟屏还没好（surface 还没起来）：挂成待投请求
                pendingPkg = pkg; pendingCls = cls; pendingCb = cb;
                return;
            }
            if (panel == null) {
                if (!attach()) {
                    fail(cb, "悬浮层加不上仪表屏（缺悬浮窗权限或被系统拒绝）");
                    return;
                }
                pendingPkg = pkg; pendingCls = cls; pendingCb = cb;   // 等 surfaceCreated
                return;
            }
            String err = launch(pkg, cls);
            if (err == null) { castPkg = pkg; ok(cb); }
            else fail(cb, err);
        } catch (Throwable t) {
            fail(cb, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ---------- 悬浮层 ----------

    /**
     * 在仪表屏上挂全屏悬浮层（窗口参数与 amap-companion ensureClusterMirror 相同，
     * 尺寸改为仪表物理分辨率，保证盖满）。
     */
    private boolean attach() {
        try {
            Display d = findClusterDisplay(app);
            if (d == null) return false;
            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);
            if (m.widthPixels > 0) vdW = m.widthPixels;
            if (m.heightPixels > 0) vdH = m.heightPixels;
            if (m.densityDpi > 0) vdDpi = m.densityDpi;

            Context dctx = app.createDisplayContext(d);
            panel = new FrameLayout(dctx);
            // 黑色兜底：虚拟屏内容没上来之前至少不是透明叠加导致的「黑屏」误判
            panel.setBackgroundColor(Color.BLACK);
            surface = new SurfaceView(dctx);
            // 关键：SurfaceView 默认在窗口 Surface 之下，overlay 窗口透明时内容看不见。
            // setZOrderOnTop 让 Surface 浮在窗口最上层，虚拟屏画面才能透出来。
            surface.setZOrderOnTop(true);
            surface.getHolder().setFormat(PixelFormat.OPAQUE);
            surface.getHolder().setFixedSize(vdW, vdH);
            surface.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) { onSurfaceReady(); }
                @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
                @Override public void surfaceDestroyed(SurfaceHolder holder) { onSurfaceGone(); }
            });
            panel.addView(surface, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    vdW, vdH,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.OPAQUE);
            clusterWm = (WindowManager) dctx.getSystemService(Context.WINDOW_SERVICE);
            clusterWm.addView(panel, lp);
            Log.i(TAG, "overlay attached on display " + d.getDisplayId()
                    + " " + vdW + "x" + vdH + "@" + vdDpi);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "attach failed: " + t);
            panel = null; surface = null; clusterWm = null;
            return false;
        }
    }

    /** surface 起来了：建虚拟屏，把挂起的投屏请求（或重投旧目标）落进去。 */
    private void onSurfaceReady() {
        try {
            Surface s = surface.getHolder().getSurface();
            if (s == null || !s.isValid()) { failPending("仪表屏 surface 无效"); return; }
            DisplayManager dm =
                    (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            vd = dm.createVirtualDisplay(VD_NAME, vdW, vdH, vdDpi, s,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            if (vd == null) { failPending("虚拟屏创建失败"); return; }
            Display vdDisplay = vd.getDisplay();
            vdId = vdDisplay.getDisplayId();
            int vdFlags = vdDisplay.getFlags();
            // 公开虚拟屏才有 FLAG_PRESENTATION；第三方应用没有 CAPTURE_VIDEO_OUTPUT 权限时，
            // ROM 会静默把虚拟屏降为私有屏，别的应用启动上去也无法渲染 → 黑屏。
            // 检测到私有屏直接报错，让服务降级到 v13 直投物理仪表屏。
            boolean isPublic = (vdFlags & Display.FLAG_PRESENTATION) != 0;
            Log.i(TAG, "virtual display " + vdId + " flags=0x"
                    + Integer.toHexString(vdFlags) + " public=" + isPublic);
            if (!isPublic) {
                try { vd.release(); } catch (Throwable ignored) {}
                vd = null; vdId = -1;
                failPending("虚拟屏为私有屏（系统未授予公开虚拟屏权限），第三方应用无法渲染，将降级直投仪表屏");
                return;
            }

            if (pendingPkg != null) {
                String p = pendingPkg, c = pendingCls;
                Callback cb = pendingCb;
                pendingPkg = null; pendingCls = null; pendingCb = null;
                String err = launch(p, c);
                if (err == null) { castPkg = p; ok(cb); }
                else fail(cb, err);
            } else if (castPkg != null) {
                // 仪表屏灭/亮导致 surface 重建：虚拟屏跟着重建，把原目标自动投回去
                String p = castPkg;
                String c = Caster.launchable(app, p);
                if (c != null) {
                    String err = launch(p, c);
                    Log.i(TAG, err == null ? "resumed " + p : "resume failed: " + err);
                }
            }
        } catch (Throwable t) {
            failPending(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** surface 没了（仪表屏灭掉）：虚拟屏作废，悬浮层保留，亮回来时 surfaceCreated 会重建。 */
    private void onSurfaceGone() {
        if (vd != null) {
            try { vd.release(); } catch (Throwable ignored) { }
            vd = null; vdId = -1;
            Log.i(TAG, "virtual display released (surface gone)");
        }
    }

    // ---------- 往虚拟屏启动 app ----------

    /** 返回 null=已启动；否则真实错误描述。 */
    private String launch(String pkg, String cls) {
        if (vd == null || vdId < 0) return "虚拟屏还没就绪";
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setClassName(pkg, cls);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // 公开 API 运行时探测：这台 ROM 允不允许往我们的虚拟屏上启别的应用。
        // 查不了就当允许，真不行 startActivity 自己会报错，两条路都照实上报。
        try {
            ActivityManager am =
                    (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            boolean allowed = am.isActivityStartAllowedOnDisplay(app, vdId, i);
            if (!allowed) return "系统不允许在虚拟屏上启动其他应用（isActivityStartAllowedOnDisplay=false）";
        } catch (Throwable ignored) { }
        try {
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(vdId);
            app.startActivity(i, o.toBundle());
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    // ---------- 撤除 ----------

    /** 撤悬浮层 + 销虚拟屏（虚拟屏里的任务随之销毁）。原车内容立刻透出来。 */
    public void teardown() {
        main.post(new Runnable() {
            @Override public void run() { teardownOnMain(); }
        });
    }

    private void teardownOnMain() {
        failPending("投屏已退出");
        if (vd != null) {
            try { vd.release(); } catch (Throwable ignored) { }
            vd = null; vdId = -1;
        }
        if (panel != null && clusterWm != null) {
            try { clusterWm.removeView(panel); } catch (Throwable ignored) { }
        }
        panel = null; surface = null; clusterWm = null; castPkg = null;
        Log.i(TAG, "teardown done");
    }

    // ---------- 回调小工具 ----------

    private void ok(Callback cb) { if (cb != null) cb.onResult(true, null); }

    private void fail(Callback cb, String err) { if (cb != null) cb.onResult(false, err); }

    private void failPending(String err) {
        if (pendingCb != null) {
            Callback cb = pendingCb;
            pendingCb = null;
            pendingPkg = null; pendingCls = null;
            cb.onResult(false, err);
        }
    }
}
