package com.ahui.clustercast

import android.content.Context
import android.content.pm.ApplicationInfo
import android.provider.Settings

/**
 * dock 全屏沉浸：给自己安装的第三方应用隐藏状态栏和底部 dock。
 *
 * 走的是系统设置里的 policy_control —— 这台车是 Android 11，WindowManager
 * 仍然读这一项（实机 `settings get global policy_control` 能读到、能改）。
 * 只把「用户自己装的包」写进列表，原车那批（system 分区）一个都不动，
 * 所以原车桌面、空调、仪表照常留 dock。
 *
 * 需要 WRITE_SECURE_SETTINGS：这权限申请不来，装包时电脑授权一次，重启不失效。
 * 写完必须回读，不能凭"putString 没抛异常"就当成功。
 */
object Immersive {

    private const val KEY = "policy_control"
    private const val SP = "pc"

    fun granted(c: Context): Boolean = try {
        c.packageManager.checkPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS, c.packageName) == 0
    } catch (t: Throwable) { false }

    /** 用户自己装的包（含我们自己），排除 system 分区和已禁用的。 */
    fun thirdParty(c: Context): List<String> {
        return try {
            val pm = c.packageManager
            pm.getInstalledApplications(0).filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 && it.enabled
            }.map { it.packageName }.distinct().sorted()
        } catch (t: Throwable) { ArrayList<String>() }
    }

    /** 开：写入 immersive.full=<三方包列表>，回读校验后给出一句能直接进日志的结论。 */
    fun on(c: Context): String {
        if (!granted(c)) return NO_PERM
        val pkgs = thirdParty(c)
        if (pkgs.isEmpty()) return "没检测到自行安装的应用，沉浸没开"
        val sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE)
        if (!sp.getBoolean("saved", false)) {
            sp.edit().putString("prev", read(c)).putBoolean("saved", true).apply()
        }
        val want = "immersive.full=" + pkgs.joinToString(",")
        if (!write(c, want)) return "写入 policy_control 被拒，沉浸没开"
        val back = read(c)
        return if (back == want) "沉浸已开，影响 " + pkgs.size + " 个自装应用（回读一致）"
        else "沉浸没生效：期望「$want」，实际「$back」"
    }

    /** 关：还原我们自己进来之前那份设置；没开过就什么都不动。 */
    fun off(c: Context): String {
        if (!granted(c)) return NO_PERM
        val sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE)
        if (!sp.getBoolean("saved", false)) return "沉浸本来就没开过，系统设置没动"
        val prev = sp.getString("prev", null)
        if (!write(c, prev)) return "清除 policy_control 被拒"
        sp.edit().clear().apply()
        val back = read(c)
        return if (back == prev) "沉浸已关" + (if (prev == null) "（已清空）" else "（已还原原设置）")
        else "沉浸没关干净：期望「$prev」，实际「$back」"
    }

    /** 自装应用会增删，列表变了才重写；返回 null 表示什么都没变。 */
    fun sync(c: Context): String? {
        val cfg = Cfg(c)
        if (!cfg.immersive()) return null
        val pkgs = thirdParty(c)
        if (pkgs.isEmpty()) return null
        val want = "immersive.full=" + pkgs.joinToString(",")
        if (read(c) == want) return null
        if (!write(c, want)) return "沉浸列表已变但写入被拒"
        return if (read(c) == want) "沉浸列表已更新（" + pkgs.size + " 个自装应用）"
        else "沉浸列表更新没生效"
    }

    fun read(c: Context): String? = try {
        Settings.Global.getString(c.contentResolver, KEY)
    } catch (t: Throwable) { null }

    private fun write(c: Context, v: String?): Boolean = try {
        Settings.Global.putString(c.contentResolver, KEY, v)
    } catch (t: Throwable) { false }

    private const val NO_PERM = "没给 WRITE_SECURE_SETTINGS 权限，沉浸开不了。" +
            "电脑执行：adb shell pm grant com.ahui.clustercast " +
            "android.permission.WRITE_SECURE_SETTINGS"
}
