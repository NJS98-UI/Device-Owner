package com.ahui.clustercast;

import android.content.Context;
import android.content.Intent;

import java.util.Arrays;
import java.util.List;

/**
 * 底下那一排预设功能按钮。每个按钮挂了若干候选入口，按顺序试：
 * 先试显式 Activity，再试包名兜底的通用 Intent，谁能在本机拉起就用谁。
 * 记录仪在这台车上没有独立可启动界面（挂在系统进程里），
 * 点了没反应就是本机确实没有这个入口，不做任何假装。
 */
public final class Presets {

    private Presets() { }

    public static class Item {
        public final String name;
        public final String[] pkgs;
        public final String[] actions;
        Item(String name, String[] pkgs, String[] actions) {
            this.name = name; this.pkgs = pkgs; this.actions = actions;
        }
    }

    public static final List<Item> ITEMS = Arrays.asList(
            new Item("投屏", new String[]{}, new String[]{}),
            new Item("空调", new String[]{"com.desaysv.svhvac"},
                    new String[]{"com.desaysv.svhvac.SHOW_PANEL"}),
            new Item("车窗/尾门", new String[]{"com.desaysv.setting"},
                    new String[]{"com.desaysv.vehiclesetting.ACTION_VEHICLE_SETTING"}),
            new Item("倒车", new String[]{"com.desaysv.ivi.vds.rvc"},
                    new String[]{"com.desaysv.intent.action.SHOW_RVC"}),
            new Item("记录仪", new String[]{"com.desaysv.dvr"},
                    new String[]{"com.desaysv.intent.action.SHOW_DVR"})
    );

    /** 返回 null 表示成功，否则是给用户看的说明文字。 */
    public static String launch(Context ctx, Item it) {
        for (String pkg : it.pkgs) {
            String cls = Caster.launchable(ctx, pkg);
            if (cls == null) continue;
            if (Caster.startOnDisplay(ctx, pkg, cls, Caster.MAIN) == null) return null;
        }
        for (String act : it.actions) {
            try {
                ctx.startActivity(new Intent(act)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return null;
            } catch (Throwable ignored) { }
        }
        return "这台车上没找到「" + it.name + "」的可用入口";
    }
}
