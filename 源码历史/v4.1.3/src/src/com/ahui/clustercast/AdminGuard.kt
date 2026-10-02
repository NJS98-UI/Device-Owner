package com.ahui.clustercast

import android.content.Context
import android.content.Intent

/**
 * 屏蔽系统那个「您的管理员不允许进行这项更改」弹窗。
 *
 * 实机查到的根因（不是我们应用造成的，也改不掉）：
 *   1) 用户 0 的基础限制里有 no_modify_accounts，来自这台 ROM 的
 *      config_defaultFirstUserRestrictions（dumpsys user 里
 *      "Restrictions: no_modify_accounts"，device policy 那两项是 null/none，
 *      所以跟设备管理器/冰狐没关系）；`pm set-user-restriction` 在这台 ROM 上
 *      解析参数就是坏的，清不掉。
 *   2) 全机唯一的账号认证器是蓝牙电话本（com.android.bluetooth 的
 *      pbapclient.AuthenticationService，sourceDir=/system/app/DsvBluetooth）。
 *      它每 20 秒试一次加账号，被上面那条限制挡掉 —— framework 就
 *      startActivity(android.accounts.CantAddAccountActivity)，
 *      一个 uid 1000、铺满主屏的半透明对话框，把正在用的应用整个盖住。
 *
 * 普通应用点不掉别人的对话框（没有输入注入权限），能做的只有把它顶下去：
 * 弹窗的前台包名固定是 framework 包 "android"，usagestats 实测能抓到这条事件，
 * 认出来说明它在上面，就把底下那个应用重新顶回前台。
 */
object AdminGuard {

    /** 连着两次轮询都看见它才算数，别把路过的系统界面当弹窗。 */
    private const val NEED_HITS = 2
    /** 它每 20 秒就来一次，压制可以频繁，日志不能。 */
    private const val LOG_COOLDOWN = 60_000L

    private var hits = 0
    private var lastAct = 0L
    private var lastLog = 0L

    /** 开关刚打开时清一次计数，免得还要等上一轮的冷却。 */
    fun reset() { hits = 0; lastAct = 0L; lastLog = 0L }

    /** 返回 null = 什么都不用做；否则是一句要写进日志的结论。 */
    fun tick(ctx: Context): String? {
        if (!TopApp.granted(ctx)) return null
        if (TopApp.top(ctx) != TopApp.FRAMEWORK) { hits = 0; return null }
        if (++hits < NEED_HITS) return null
        hits = 0
        val now = System.currentTimeMillis()
        if (now - lastAct < 3000) return null
        lastAct = now
        val under = TopApp.under(ctx)
        val ok = if (under == null) goHome(ctx)
        else Caster.moveToDisplay(ctx, under, Caster.MAIN)
        if (now - lastLog < LOG_COOLDOWN) return null
        lastLog = now
        val name = if (under == null) null
        else CastService.inst()?.label(under) ?: under
        return if (ok) "系统弹窗（管理员不允许）已顶掉" +
                (if (name == null) "，回到桌面" else "，回到 " + name)
        else "系统弹窗顶不掉：它又压回来了（under=" + under + "）"
    }

    private fun goHome(ctx: Context): Boolean = try {
        ctx.startActivity(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (t: Throwable) { false }
}
