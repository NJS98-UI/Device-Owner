package com.ahui.clustercast;

import android.content.Context;
import android.content.Intent;

/**
 * 屏蔽系统那个「您的管理员不允许进行这项更改」弹窗。
 *
 * 实机抓包查到的根因（不是我们应用造成的，也改不掉）：
 *   1) 这台 ROM 的用户 0 基础限制里带 no_modify_accounts（dumpsys user 里
 *      "Restrictions: no_modify_accounts"），`pm set-user-restriction` 在这台 ROM
 *      上解析参数就是坏的，清不掉。
 *   2) 抓包 60 分钟里，每 20 秒就有一次 addAccount 被这条限制挡掉，framework 于是
 *      startActivity(android.accounts.CantAddAccountActivity) —— 一个 uid 1000、
 *      铺满主屏的半透明对话框，把正在用的应用整个盖住。
 *      嫌疑进程是 uid 10016（com.mojoxing.light），它才是发起方，我们动不了它。
 *
 * 普通应用点不掉别人的对话框（没有输入注入权限），能做的只有把它顶下去：
 * 弹窗的前台包名固定是 framework 包 "android"，usagestats 实测能抓到这条事件，
 * 认出来说明它在上面，就把底下那个应用重新顶回前台。
 */
public final class AdminGuard {

    private AdminGuard() { }

    /** 连着两次轮询都看见它才算数，别把路过的系统界面当弹窗。 */
    private static final int NEED_HITS = 2;
    /** 它每 20 秒就来一次，压制可以频繁，日志不能。 */
    private static final long LOG_COOLDOWN = 60_000L;

    private static int hits = 0;
    private static long lastAct = 0L;
    private static long lastLog = 0L;

    /** 开关刚打开时清一次计数，免得还要等上一轮的冷却。 */
    public static void reset() { hits = 0; lastAct = 0L; lastLog = 0L; }

    /** 返回 null = 什么都不用做；否则是一句要写进日志的结论。 */
    public static synchronized String tick(Context ctx) {
        if (!TopApp.granted(ctx)) return null;
        if (!TopApp.FRAMEWORK.equals(TopApp.top(ctx))) { hits = 0; return null; }
        if (++hits < NEED_HITS) return null;
        hits = 0;
        long now = System.currentTimeMillis();
        if (now - lastAct < 3000) return null;
        lastAct = now;
        String under = TopApp.under(ctx);
        boolean ok = under == null ? goHome(ctx)
                : Caster.moveToDisplay(ctx, under, Caster.MAIN);
        if (now - lastLog < LOG_COOLDOWN) return null;
        lastLog = now;
        String name = null;
        if (under != null) {
            CastService s = CastService.inst();
            if (s != null) name = s.label(under);
            if (name == null) name = under;
        }
        return ok ? "系统弹窗（管理员不允许）已顶掉" +
                        (name == null ? "，回到桌面" : "，回到 " + name)
                : "系统弹窗顶不掉：它又压回来了（under=" + under + "）";
    }

    private static boolean goHome(Context ctx) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
