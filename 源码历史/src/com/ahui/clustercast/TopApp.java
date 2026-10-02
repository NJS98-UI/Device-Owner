package com.ahui.clustercast;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

/**
 * 取当前前台应用。需要「有权限访问使用情况」这一项普通用户授权
 * （设置 → 特殊应用访问 → 使用情况访问权限），不是 root、不是 Shizuku。
 * 没授权时 get() 返回 null，调用方退回"上次选定的目标"。
 */
public class TopApp {

    public static boolean granted(Context c) {
        try {
            return c.getPackageManager()
                    .checkPermission(android.Manifest.permission.PACKAGE_USAGE_STATS,
                            c.getPackageName()) == 0;
        } catch (Throwable t) { return false; }
    }

    /** 这台 ROM 没有"使用情况访问权限"设置页，打不开时由调用方改用 adb 授权。 */
    public static boolean request(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .setData(Uri.parse("package:" + c.getPackageName())));
            return true;
        } catch (Throwable t) { return false; }
    }

    public static String get(Context c) {
        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageEvents ev = usm.queryEvents(now - 10_000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int t = e.getEventType();
                if (t == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.getPackageName();
            }
            return last;
        } catch (Throwable t) { return null; }
    }
}
