package com.ahui.clustercast;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;

/** 把任意应用启动到仪表屏（display 2），全程只用公开 API，不需要 root / Shizuku / 无障碍。 */
public class Caster {

    public static final String TAG = "Caster";
    public static final int CLUSTER = 2;

    public static String launchable(Context ctx, String pkg) {
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
        List<ResolveInfo> rs = ctx.getPackageManager().queryIntentActivities(probe, 0);
        if (rs != null && !rs.isEmpty()) return rs.get(0).activityInfo.name;
        try {
            return ctx.getPackageManager().getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                    .activities[0].name;
        } catch (Throwable t) { return null; }
    }

    /** 在指定屏启动；MULTIPLE_TASK 保证主屏那份实例不受影响。 */
    public static boolean startOnDisplay(Context ctx, String pkg, String cls, int displayId) {
        if (pkg == null || cls == null) return false;
        try {
            Intent it = new Intent(Intent.ACTION_MAIN);
            it.setClassName(pkg, cls);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(displayId);
            ctx.startActivity(it, opts.toBundle());
            Log.i(TAG, "startOnDisplay " + pkg + "/" + cls + " -> display " + displayId);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startOnDisplay failed: " + t);
            return false;
        }
    }

    /** 该屏是否亮着。仪表屏由 QNX 侧供电，Android 侧偶尔报 OFF，所以只作参考。 */
    public static boolean displayAlive(Context ctx, int displayId) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        for (Display d : dm.getDisplays())
            if (d.getDisplayId() == displayId && d.getState() != Display.STATE_OFF) return true;
        return false;
    }

    public static List<Display> aliveDisplays(Context ctx) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        List<Display> out = new ArrayList<>();
        for (Display d : dm.getDisplays()) if (d.getState() != Display.STATE_OFF) out.add(d);
        return out;
    }
}
