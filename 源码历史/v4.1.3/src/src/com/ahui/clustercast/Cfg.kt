package com.ahui.clustercast

import android.content.Context

/** 记住投屏目标、跟随前台、投屏仪表档位、音乐卡片对接、高德禁用状态、两个界面开关。 */
class Cfg(c: Context) {

    private val sp = c.getSharedPreferences("cast", Context.MODE_PRIVATE)

    fun pkg(): String? = sp.getString("pkg", null)
    fun cls(): String? = sp.getString("cls", null)
    fun label(): String? = sp.getString("label", null)

    fun setTarget(pkg: String, cls: String, label: String) {
        sp.edit().putString("pkg", pkg).putString("cls", cls)
                .putString("label", label).apply()
    }

    fun followTop(): Boolean = sp.getBoolean("follow_top", true)
    fun setFollowTop(on: Boolean) { sp.edit().putBoolean("follow_top", on).apply() }

    /** 投屏时把仪表切到哪一档：Vd.THEME_SIMPLE(4) 或 Vd.THEME_NAVI(3)。默认极简。 */
    fun castTheme(): Int = sp.getInt("cast_theme", Vd.THEME_SIMPLE)
    fun setCastTheme(t: Int) { sp.edit().putInt("cast_theme", t).apply() }

    /** 把第三方音乐的歌名/歌手推上总线，让原车桌面那张音乐卡片也能显示。默认开。 */
    fun cardMirror(): Boolean = sp.getBoolean("card", true)
    fun setCardMirror(on: Boolean) { sp.edit().putBoolean("card", on).apply() }

    /** 顶掉系统那个「您的管理员不允许进行这项更改」弹窗。默认关，用户自己开。 */
    fun hideAdmin(): Boolean = sp.getBoolean("hide_admin", false)
    fun setHideAdmin(on: Boolean) { sp.edit().putBoolean("hide_admin", on).apply() }

    /** 给自己安装的第三方应用开全屏沉浸（隐藏状态栏和底部 dock）。默认关。 */
    fun immersive(): Boolean = sp.getBoolean("immersive", false)
    fun setImmersive(on: Boolean) { sp.edit().putBoolean("immersive", on).apply() }

    /** 读到 R 挡就自动切到倒车页开我们自己的环视画面。默认开。 */
    fun autoR(): Boolean = sp.getBoolean("auto_r", true)
    fun setAutoR(on: Boolean) { sp.edit().putBoolean("auto_r", on).apply() }

    /**
     * 「高德是我们禁的」必须落盘。
     * 否则投屏中途进程被杀，高德就永久停在禁用状态，没人负责恢复。
     */
    fun amapOffByUs(): Boolean = sp.getBoolean("amap_off_by_us", false)
    fun setAmapOffByUs(on: Boolean) { sp.edit().putBoolean("amap_off_by_us", on).apply() }
}
