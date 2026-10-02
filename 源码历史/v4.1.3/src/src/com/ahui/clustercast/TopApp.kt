package com.ahui.clustercast

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 取当前前台应用。需要「有权限访问使用情况」这一项普通用户授权，
 * 不是 root、不是 Shizuku。没授权时 get() 返回 null，调用方退回"上次选定的目标"。
 *
 * 这台 ROM 的 usagestats 实测会记到 framework 自己的前台事件
 * （抓到的原样一条：package=android class=android.accounts.CantAddAccountActivity），
 * 所以 top() 能认出「系统弹窗盖在上面」这件事。
 */
object TopApp {

    private const val WINDOW_MS = 10 * 60 * 1000L

    /** 「您的管理员不允许进行这项更改」这类弹窗跑在 framework 包 android 里。 */
    const val FRAMEWORK = "android"

    fun granted(c: Context): Boolean = try {
        c.packageManager.checkPermission(
                android.Manifest.permission.PACKAGE_USAGE_STATS, c.packageName) == 0
    } catch (t: Throwable) { false }

    /** 这台 ROM 没有"使用情况访问权限"设置页，打不开时由调用方改用 adb 授权。 */
    fun request(c: Context): Boolean = try {
        c.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .setData(Uri.parse("package:" + c.packageName)))
        true
    } catch (t: Throwable) { false }

    /** 投屏目标：跳过设置页、桌面和 framework 弹窗。 */
    fun get(c: Context): String? {
        val me = c.packageName
        return lastOf(history(c)) { it != me && it != FRAMEWORK && it != homePackage(c) }
    }

    /** 当前前台包名，什么都不跳；等于 FRAMEWORK 就说明系统弹窗在上面。 */
    fun top(c: Context): String? = history(c).lastOrNull()

    /** 弹窗底下该被顶回前台的那个应用（我们自己的页面也算）。 */
    fun under(c: Context): String? {
        return lastOf(history(c)) { it != FRAMEWORK && it != homePackage(c) }
    }

    /** 最近十分钟的前台包名序列，连续重复只留一次；没授权返回空表。 */
    private fun history(c: Context): List<String> {
        val out = ArrayList<String>()
        try {
            val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val ev = usm.queryEvents(now - WINDOW_MS, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
                val p = e.packageName ?: continue
                if (out.isNotEmpty() && out.last() == p) continue
                out.add(p)
            }
        } catch (t: Throwable) { return ArrayList() }
        return out
    }

    private fun lastOf(list: List<String>, keep: (String) -> Boolean): String? {
        for (i in list.indices.reversed()) if (keep(list[i])) return list[i]
        return null
    }

    private fun homePackage(c: Context): String? = try {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        c.packageManager.resolveActivity(i, 0)?.activityInfo?.packageName
    } catch (t: Throwable) { null }
}
