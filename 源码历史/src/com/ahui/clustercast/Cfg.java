package com.ahui.clustercast;

import android.content.Context;
import android.content.SharedPreferences;

/** 记住投屏目标 + 是否自动跟随前台应用。 */
public class Cfg {

    private final SharedPreferences sp;

    public Cfg(Context c) { sp = c.getSharedPreferences("cast", Context.MODE_PRIVATE); }

    public String pkg()  { return sp.getString("pkg", null); }
    public String cls()  { return sp.getString("cls", null); }
    public String label(){ return sp.getString("label", null); }

    public void setTarget(String pkg, String cls, String label) {
        sp.edit().putString("pkg", pkg).putString("cls", cls).putString("label", label).apply();
    }

    public boolean followTop() { return sp.getBoolean("follow_top", false); }
    public void setFollowTop(boolean on) { sp.edit().putBoolean("follow_top", on).apply(); }

    /** 极简模式：仪表屏显示我们的播放页，应用留在主屏。默认开。 */
    public boolean simple() { return sp.getBoolean("simple", true); }
    public void setSimple(boolean on) { sp.edit().putBoolean("simple", on).apply(); }

    /** 把第三方音乐的歌名/歌手推上总线，让原车桌面那张音乐卡片也能显示。默认开。 */
    public boolean cardMirror() { return sp.getBoolean("card", true); }
    public void setCardMirror(boolean on) { sp.edit().putBoolean("card", on).apply(); }
}
