package com.jietu.clustercast;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.util.Log;

import java.util.List;

/**
 * 把任意应用启动到仪表屏（display 2），全程只用公开 API，不需要 root / Shizuku / 无障碍。
 * 由 v5.1.0 的 Caster.java 原样移植（仅改包名），投屏契约未变。
 */
public final class Caster {

    private Caster() { }

    public static final String TAG = "Caster";
    public static final int CLUSTER = 2;
    public static final int MAIN = 0;

    /** 原车高德，投屏期间按用户要求禁用、退出投屏时恢复的就是它。 */
    public static final String AMAP = "com.desaysv.jetour.t1n.psmap";

    public static String launchable(Context ctx, String pkg) {
        Intent probe = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
        List<ResolveInfo> rs = ctx.getPackageManager().queryIntentActivities(probe, 0);
        if (rs != null && !rs.isEmpty()) return rs.get(0).activityInfo.name;
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            ActivityInfo[] ais = pi.activities;
            return (ais != null && ais.length > 0) ? ais[0].name : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 在指定屏启动。返回 null=成功，否则是真实异常描述（写进日志，不猜）。
     * own=true 时不能加 MULTIPLE_TASK：那样每次三指左滑都会在仪表屏上叠一个新任务
     * （实机抓到同时有 2835/2836/2843 三个 StackId）。
     * own=false（整屏投第三方应用）时保留 MULTIPLE_TASK，尽量不动主屏那份实例。
     *
     * 尺寸由我们写成整块物理屏（setLaunchBounds），投屏布满整个仪表屏。
     * 这台 ROM 要是忽略 launchBounds，投完照样能在日志里量出实际尺寸，不谎报铺满。
     */
    public static String startOnDisplay(Context ctx, String pkg, String cls, int displayId) {
        return startOnDisplay(ctx, pkg, cls, displayId, false);
    }

    public static String startOnDisplay(Context ctx, String pkg, String cls, int displayId,
                                        boolean own) {
        if (pkg == null || cls == null) return "没有可启动的界面";
        try {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.setClassName(pkg, cls);
            int flags = Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP;
            if (!own) flags = flags | Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
            it.addFlags(flags);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(displayId);
            Rect r = fullBounds(ctx, displayId);
            if (r != null) {
                try {
                    opts.setLaunchBounds(r);
                } catch (Throwable t) {
                    Log.w(TAG, "setLaunchBounds rejected: " + t);
                }
            }
            ctx.startActivity(it, opts.toBundle());
            Log.i(TAG, "startOnDisplay " + pkg + "/" + cls + " -> display " + displayId +
                    (own ? " (own)" : ""));
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "startOnDisplay failed: " + t);
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 目标屏的整块物理区域，拿不到返回 null（那就交给系统默认）。 */
    public static Rect fullBounds(Context ctx, int displayId) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        for (android.view.Display d : dm.getDisplays()) {
            if (d.getDisplayId() != displayId) continue;
            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);
            if (m.widthPixels <= 0 || m.heightPixels <= 0) return null;
            return new Rect(0, 0, m.widthPixels, m.heightPixels);
        }
        return null;
    }

    /**
     * 把某个包的应用退回指定屏（通常是主屏）。
     * 用 LAUNCHER intent + SINGLE_TOP，系统会把已有那份实例挪过去，
     * 不会杀进程 —— 酷狗这类退回去歌也不会停。这条在这台车上实测可行。
     */
    public static boolean moveToDisplay(Context ctx, String pkg, int displayId) {
        if (pkg == null) return false;
        try {
            String cls = launchable(ctx, pkg);
            if (cls == null) return false;
            Intent it = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, cls);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED |
                    Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(displayId);
            ctx.startActivity(it, opts.toBundle());
            Log.i(TAG, "moveToDisplay " + pkg + " -> display " + displayId);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "moveToDisplay failed: " + t);
            return false;
        }
    }

    /**
     * 禁用 / 恢复原车高德。投屏期间禁用、退出投屏恢复（用户要求的全自动对称操作）。
     * 返回 null 表示成功，否则是异常描述。
     */
    public static String setAmapEnabled(Context ctx, boolean enabled) {
        try {
            ctx.getPackageManager().setApplicationEnabledSetting(AMAP,
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "setAmapEnabled(" + enabled + ") rejected: " + t);
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 该屏是否亮着。仪表屏由 QNX 侧供电，Android 侧偶尔报 OFF，所以只作参考。 */
    public static boolean displayAlive(Context ctx, int displayId) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        for (android.view.Display d : dm.getDisplays())
            if (d.getDisplayId() == displayId
                    && d.getState() != android.view.Display.STATE_OFF) return true;
        return false;
    }

    /** 投屏失败时把系统能看到的屏全列出来：id、状态、尺寸，日志里一眼定位。 */
    public static String describeDisplays(Context ctx) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        StringBuilder sb = new StringBuilder();
        for (android.view.Display d : dm.getDisplays()) {
            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);
            if (sb.length() > 0) sb.append(" | ");
            sb.append("屏").append(d.getDisplayId()).append("(状态").append(d.getState())
                    .append(',').append(m.widthPixels).append('x').append(m.heightPixels).append(')');
        }
        return sb.toString();
    }
}
