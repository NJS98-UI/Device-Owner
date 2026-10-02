package com.ahui.clustercast

import android.content.Context
import android.content.Intent

/**
 * 底下那一排预设功能按钮。每个按钮挂了若干候选入口，按顺序试：
 * 先试显式 Activity，再试包名兜底的通用 Intent，谁能在本机拉起就用谁。
 * 记录仪在这台车上没有独立可启动界面（挂在系统进程里），
 * 点了没反应就是本机确实没有这个入口，不做任何假装。
 */
object Presets {

    data class Item(val name: String, val pkgs: Array<String>, val actions: Array<String>)

    val ITEMS = listOf(
            Item("投屏", arrayOf(), arrayOf()),
            Item("空调", arrayOf("com.desaysv.svhvac"),
                    arrayOf("com.desaysv.svhvac.SHOW_PANEL")),
            Item("车窗/尾门", arrayOf("com.desaysv.setting"),
                    arrayOf("com.desaysv.vehiclesetting.ACTION_VEHICLE_SETTING")),
            Item("倒车", arrayOf("com.desaysv.ivi.vds.rvc"),
                    arrayOf("com.desaysv.intent.action.SHOW_RVC")),
            Item("记录仪", arrayOf("com.desaysv.dvr"),
                    arrayOf("com.desaysv.intent.action.SHOW_DVR"))
    )

    /** 返回 null 表示成功，否则是给用户看的说明文字。 */
    fun launch(ctx: Context, it: Item): String? {
        for (pkg in it.pkgs) {
            val cls = Caster.launchable(ctx, pkg) ?: continue
            if (Caster.startOnDisplay(ctx, pkg, cls, Caster.MAIN) == null) return null
        }
        for (act in it.actions) {
            try {
                ctx.startActivity(Intent(act)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return null
            } catch (ignored: Throwable) { }
        }
        return "这台车上没找到「" + it.name + "」的可用入口"
    }
}
