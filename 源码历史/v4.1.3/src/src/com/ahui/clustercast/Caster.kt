package com.ahui.clustercast

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.util.Log

/** 把任意应用启动到仪表屏（display 2），全程只用公开 API，不需要 root / Shizuku / 无障碍。 */
object Caster {

    const val TAG = "Caster"
    const val CLUSTER = 2
    const val MAIN = 0

    /** 原车高德，导航档投屏期间禁用的就是它。 */
    const val AMAP = "com.desaysv.jetour.t1n.psmap"

    fun launchable(ctx: Context, pkg: String): String? {
        val probe = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val rs = ctx.packageManager.queryIntentActivities(probe, 0)
        if (rs != null && rs.isNotEmpty()) return rs[0].activityInfo.name
        return try {
            ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                    .activities[0].name
        } catch (t: Throwable) { null }
    }

    /**
     * 在指定屏启动。返回 null=成功，否则是真实异常描述（写进日志，不猜）。
     * own=true（投我们自己的页面）时不能加 MULTIPLE_TASK：那样每次三指左滑都会在
     * 仪表屏上叠一个新任务（实机抓到同时有 2835/2836/2843 三个 StackId）。
     * own=false（整屏投第三方应用）时保留 MULTIPLE_TASK，尽量不动主屏那份实例。
     */
    @JvmOverloads
    fun startOnDisplay(ctx: Context, pkg: String?, cls: String?, displayId: Int,
                       own: Boolean = false): String? {
        if (pkg == null || cls == null) return "没有可启动的界面"
        return try {
            val it = Intent(Intent.ACTION_MAIN)
            it.setClassName(pkg, cls)
            var flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            if (!own) flags = flags or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            it.addFlags(flags)
            val opts = ActivityOptions.makeBasic()
            opts.setLaunchDisplayId(displayId)
            ctx.startActivity(it, opts.toBundle())
            Log.i(TAG, "startOnDisplay $pkg/$cls -> display $displayId" +
                    if (own) " (own)" else "")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "startOnDisplay failed: $t")
            t.javaClass.simpleName + ": " + t.message
        }
    }

    /**
     * 把某个包的应用退回指定屏（通常是主屏）。
     * 用 LAUNCHER intent + SINGLE_TOP，系统会把已有那份实例挪过去，
     * 不会杀进程 —— 酷狗这类退回去歌也不会停。这条在这台车上实测可行。
     */
    fun moveToDisplay(ctx: Context, pkg: String?, displayId: Int): Boolean {
        if (pkg == null) return false
        return try {
            val cls = launchable(ctx, pkg) ?: return false
            val it = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, cls)
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val opts = ActivityOptions.makeBasic()
            opts.setLaunchDisplayId(displayId)
            ctx.startActivity(it, opts.toBundle())
            Log.i(TAG, "moveToDisplay $pkg -> display $displayId")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "moveToDisplay failed: $t")
            false
        }
    }

    /**
     * 禁用 / 恢复原车高德。
     * 普通应用调 setApplicationEnabledSetting 需要 signature|privileged 权限，
     * 大概率被拒 —— 成功就成功，失败由调用方写日志并退回"看门狗"方案，绝不假装禁用了。
     * 返回 null 表示成功，否则是异常描述。
     */
    fun setAmapEnabled(ctx: Context, enabled: Boolean): String? {
        return try {
            ctx.packageManager.setApplicationEnabledSetting(AMAP,
                    if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)
            null
        } catch (t: Throwable) {
            Log.w(TAG, "setAmapEnabled($enabled) rejected: $t")
            t.javaClass.simpleName + ": " + t.message
        }
    }

    fun amapEnabled(ctx: Context): Boolean = try {
        ctx.packageManager.getApplicationEnabledSetting(AMAP) !=
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (t: Throwable) { true }

    /** 该屏是否亮着。仪表屏由 QNX 侧供电，Android 侧偶尔报 OFF，所以只作参考。 */
    fun displayAlive(ctx: Context, displayId: Int): Boolean {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        for (d in dm.displays)
            if (d.displayId == displayId && d.state != android.view.Display.STATE_OFF) return true
        return false
    }

    /** 投屏失败时把系统能看到的屏全列出来：id、状态、尺寸，日志里一眼定位。 */
    fun describeDisplays(ctx: Context): String {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val sb = StringBuilder()
        for (d in dm.displays) {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getRealMetrics(m)
            if (sb.isNotEmpty()) sb.append(" | ")
            sb.append("屏").append(d.displayId).append("(状态").append(d.state)
                    .append(',').append(m.widthPixels).append('x').append(m.heightPixels).append(')')
        }
        return sb.toString()
    }
}
