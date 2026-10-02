package com.ahui.clustercast;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * 取当前前台应用。需要「有权限访问使用情况」这一项普通用户授权，
 * 不是 root、不是 Shizuku。没授权时 get() 返回 null，调用方退回"上次选定的目标"。
 *
 * 这台 ROM 的 usagestats 实测会记到 framework 自己的前台事件（抓到的原样一条：
 * package=android class=android.accounts.CantAddAccountActivity），所以 top()
 * 能认出「系统弹窗盖在上面」这件事，AdminGuard 靠它压制那个弹窗。
 *
 * 由 v4.7.5 的 TopApp.kt 逐行等价翻译。
 */
public final class TopApp {

    private TopApp() { }

    private static final long WINDOW_MS = 10 * 60 * 1000L;

    /** 「您的管理员不允许进行这项更改」这类弹窗跑在 framework 包 android 里。 */
    public static final String FRAMEWORK = "android";

    /** 取证 C1：弹窗的发起方是这个第三方进程，别把它当「弹窗底下的应用」顶上来。 */
    public static final String ACCOUNT_SPAMMER = "com.mojoxing.light";

    public static boolean granted(Context c) {
        try {
            return c.getPackageManager().checkPermission(
                    android.Manifest.permission.PACKAGE_USAGE_STATS, c.getPackageName()) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 这台 ROM 没有"使用情况访问权限"设置页，打不开时由调用方改用 adb 授权。 */
    public static boolean request(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .setData(Uri.parse("package:" + c.getPackageName())));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 投屏目标：最近一次真正切到前台的应用。窗口必须够宽 —— 打开 A 之后两三分钟
     * 才滑动是常态，只查 10 秒会把前台丢掉、让调用方退回旧目标（投错应用）。
     * 跳过我们自己的设置页、桌面、framework 弹窗，还有那个每 20 秒发起一次
     * addAccount 的第三方进程（取证 C1：com.mojoxing.light uid 10016）——
     * 它偶尔会留一条前台事件，跟着前台模式把它投上仪表就荒唐了。
     */
    public static String get(Context c) {
        final String me = c.getPackageName();
        final String home = homePackage(c);
        return lastOf(history(c), new Keep() {
            @Override public boolean keep(String it) {
                return !it.equals(me) && !it.equals(FRAMEWORK)
                        && !it.equals(ACCOUNT_SPAMMER)
                        && (home == null || !it.equals(home));
            }
        });
    }

    /** 当前前台包名，什么都不跳；等于 FRAMEWORK 就说明系统弹窗压在上面。 */
    public static String top(Context c) {
        List<String> h = history(c);
        return h.isEmpty() ? null : h.get(h.size() - 1);
    }

    /**
     * 弹窗底下该被顶回前台的那个应用（我们自己的页面也算）。
     * 必须跳过弹窗发起方本身（取证 C1：com.mojoxing.light），
     * 顶它就会立刻再弹一次，等于自己按循环键。
     */
    public static String under(Context c) {
        final String home = homePackage(c);
        return lastOf(history(c), new Keep() {
            @Override public boolean keep(String it) {
                return !it.equals(FRAMEWORK) && !it.equals(ACCOUNT_SPAMMER)
                        && (home == null || !it.equals(home));
            }
        });
    }

    private interface Keep { boolean keep(String pkg); }

    /** 最近十分钟的前台包名序列，连续重复只留一次；没授权返回空表。 */
    private static List<String> history(Context c) {
        ArrayList<String> out = new ArrayList<>();
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageEvents ev = usm.queryEvents(now - WINDOW_MS, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND) continue;
                String p = e.getPackageName();
                if (p == null) continue;
                if (!out.isEmpty() && out.get(out.size() - 1).equals(p)) continue;
                out.add(p);
            }
        } catch (Throwable t) {
            return new ArrayList<>();
        }
        return out;
    }

    private static String lastOf(List<String> list, Keep keep) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (keep.keep(list.get(i))) return list.get(i);
        }
        return null;
    }

    private static String homePackage(Context c) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = c.getPackageManager().resolveActivity(i, 0);
            if (ri == null) return null;
            ActivityInfo ai = ri.activityInfo;
            return (ai == null) ? null : ai.packageName;
        } catch (Throwable t) {
            return null;
        }
    }
}
