package com.ahui.clustercast;

import android.content.Context;
import android.provider.Settings;

/**
 * 沉浸 dock —— 用的是原车自己的那个开关，不是 AOSP 的 policy_control。
 *
 * 取证包里的实锤：系统设置表里有一行 `com.desaysv.status.bar.status`，
 * before_system.txt:57 是 2，用户在下拉里收起那一排之后 after_system.txt 变成 0。
 * 也就是说 dock 就是 systemui 画的那排 Android 窗口（日志里到处是
 * NavigationBarViewForBottomT1N / NavigationBar0，进程 1140 = com.android.systemui），
 * 它自己靠这个 settings 键显隐 —— 之前「dock 由 QNX 渲染」的结论是错的。
 *
 * 我们做的只是替用户按那个开关：写 0 收起、写回进入前的值还原，写完立即回读，
 * 回读不等于想要的值就照实报失败。写 system 表需要 WRITE_SECURE_SETTINGS：
 * 申请不来，只能电脑上授权一次（重启不失效）：
 *   adb shell pm grant com.ahui.clustercast android.permission.WRITE_SECURE_SETTINGS
 * 没授权时 putInt 抛异常，我们把真实原因写进日志，绝不假装收起了。
 *
 * 由 v4.7.5 的 Dock.kt 逐行等价翻译，契约未变。
 */
public final class Dock {

    private Dock() { }

    public static final String KEY = "com.desaysv.status.bar.status";
    public static final int HIDDEN = 0;

    /** 现在能不能写这条设置（授权没给就是 false，界面据此说人话）。 */
    public static boolean canWrite(Context c) {
        try {
            Integer cur = peek(c);
            int v = (cur == null) ? 2 : cur;      // 键不存在时拿默认值试写
            return Settings.System.putInt(c.getContentResolver(), KEY, v);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前值；键不存在或读不到返回 null（照实返回，不猜）。 */
    public static Integer peek(Context c) {
        try {
            int v = Settings.System.getInt(c.getContentResolver(), KEY);
            return (v == Integer.MIN_VALUE) ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 收起 dock：先把当前值存进 prefs（只存第一次，别把「已收起」当原值存进去），
     * 再写 0、回读。返回的一句结论直接进日志。
     */
    public static String hide(Context c) {
        Integer cur = peek(c);
        if (cur == null) return "读不到 " + KEY + "，dock 保持原样";
        if (cur != HIDDEN) saveOrig(c, cur);
        String r = write(c, HIDDEN);
        return (r == null)
                ? "dock 已收起（systemui 的导航栏，原值 " + cur + " 已记下）"
                : "dock 收起失败：" + r;
    }

    /** 还原 dock：写回进入前存下的值；没记过返回 null（没是我们收的，不擅自还原）。 */
    public static String restore(Context c) {
        android.content.SharedPreferences p = prefs(c);
        int orig = p.getInt("dock_orig", -1);
        if (orig < 0) return null;
        p.edit().putInt("dock_orig", -1).apply();
        return write(c, orig);
    }

    private static void saveOrig(Context c, int v) {
        android.content.SharedPreferences p = prefs(c);
        if (p.getInt("dock_orig", -1) < 0) p.edit().putInt("dock_orig", v).apply();
    }

    private static String write(Context c, int v) {
        boolean ok;
        try {
            ok = Settings.System.putInt(c.getContentResolver(), KEY, v);
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + "（没授权的话：adb shell pm grant " +
                    c.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS）";
        }
        Integer back = peek(c);
        if (!ok) return "系统拒写";
        if (back == null || back != v)
            return "回读是 " + back + "，不是想要的 " + v + " —— 这条键可能被系统看管";
        return null;
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("cast", Context.MODE_PRIVATE);
    }
}
