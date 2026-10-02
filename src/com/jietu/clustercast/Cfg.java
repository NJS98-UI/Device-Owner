package com.jietu.clustercast;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 记住投屏目标、跟随前台、投屏仪表档位、投屏整屏填充、
 * 导航区压制和「高德是我们禁的」状态（读取→保存）。
 * 由 v5.1.0 的 Cfg.java 裁剪移植：只留投屏相关的六项。
 */
public class Cfg {

    private final SharedPreferences sp;

    public Cfg(Context c) {
        sp = c.getSharedPreferences("cast", Context.MODE_PRIVATE);
    }

    public String pkg() { return sp.getString("pkg", null); }
    public String cls() { return sp.getString("cls", null); }
    public String label() { return sp.getString("label", null); }

    public void setTarget(String pkg, String cls, String label) {
        sp.edit().putString("pkg", pkg).putString("cls", cls)
                .putString("label", label).apply();
    }

    public boolean followTop() { return sp.getBoolean("follow_top", true); }
    public void setFollowTop(boolean on) { sp.edit().putBoolean("follow_top", on).apply(); }

    /** 投屏时仪表固定用导航档（v15.9 起不再提供极简模式选项，历史存的偏好也作废）。 */
    public int castTheme() { return Vd.THEME_NAVI; }

    /** 投屏整屏填充：launchBounds 由我们写成仪表整屏，不让原车布局留边。默认开。 */
    public boolean castFill() { return sp.getBoolean("cast_fill", true); }
    public void setCastFill(boolean on) { sp.edit().putBoolean("cast_fill", on).apply(); }

    /**
     * 总线压制原车导航（CAR_LAN 721699/721702）：防止高德后台吐状态导致仪表渲染
     * 「地图信息准备中」盖住投屏。默认开。投屏原车高德时自动放行。
     */
    public boolean suppressNaviCluster() { return sp.getBoolean("suppress_navi", true); }
    public void setSuppressNaviCluster(boolean on) { sp.edit().putBoolean("suppress_navi", on).apply(); }

    /**
     * 「高德是我们禁的」必须落盘。
     * 否则投屏中途进程被杀，高德就永久停在禁用状态，没人负责恢复。
     */
    public boolean amapOffByUs() { return sp.getBoolean("amap_off_by_us", false); }
    public void setAmapOffByUs(boolean on) { sp.edit().putBoolean("amap_off_by_us", on).apply(); }

    /**
     * 「高德是我们藏起来的（Device Owner setApplicationHidden）」同样落盘。
     * 与 amapOffByUs 是两条互斥路径，恢复时按实际用的那条解。
     */
    public boolean amapHiddenByUs() { return sp.getBoolean("amap_hidden_by_us", false); }
    public void setAmapHiddenByUs(boolean on) { sp.edit().putBoolean("amap_hidden_by_us", on).apply(); }

    /** 仪表盘悬浮音乐卡片（右侧音乐详情，非投屏也显示）。默认关。 */
    public boolean clusterMusic() { return sp.getBoolean("cluster_music", false); }
    public void setClusterMusic(boolean on) { sp.edit().putBoolean("cluster_music", on).apply(); }

    // ---------- 开门迎宾语 ----------

    /** 开门迎宾语总开关（车门开/关变化时播语音）。默认关。 */
    public boolean greeting() { return sp.getBoolean("greeting", true); }
    public void setGreeting(boolean on) { sp.edit().putBoolean("greeting", on).apply(); }

    /**
     * 车门状态 cmdId（全部在模块 327684，2026-09-29 实车扫描标定，1=开 0=关）：
     * 0=主驾 15 1=副驾 16 2=左后 17 3=右后 18 4=尾门 162（getTrunkDoorState 同源；
     * 327681/92 是动作信号读数反着，别用它判开关）。0=待确认不监听。
     */
    public int doorCmdId(int door) {
        return sp.getInt("door_cmd_" + door, new int[]{15, 16, 17, 18, 162}[door]);
    }
    public void setDoorCmdId(int door, int cmdId) {
        sp.edit().putInt("door_cmd_" + door, cmdId).apply();
    }

    /** 事件用自定义 MP3（true）还是内置 TTS 播报（false）。事件号 = 门*2 + (开?1:0) */
    public boolean greetCustom(int ev) { return sp.getBoolean("greet_custom_" + ev, false); }
    public void setGreetCustom(int ev, boolean custom) {
        sp.edit().putBoolean("greet_custom_" + ev, custom).apply();
    }

    /** 事件内置播报文本（TTS）。默认开门欢迎、关门道别。 */
    public String greetText(int ev) {
        return sp.getString("greet_text_" + ev, ev % 2 == 1 ? "欢迎上车" : "感谢乘坐");
    }
    public void setGreetText(int ev, String text) {
        sp.edit().putString("greet_text_" + ev, text).apply();
    }

    /** 每事件音频配置（JSON，结构见 GreetAudio）。空=没配音频，播 TTS 文本。 */
    public String greetAudio(int ev) { return sp.getString("greet_audio_" + ev, ""); }
    public void setGreetAudio(int ev, String json) {
        sp.edit().putString("greet_audio_" + ev, json).apply();
    }

    /** 通用音频配置读取（门事件传事件号字符串，挡位传 gear_1~4），同一存储前缀。 */
    public String audio(String storeKey) { return sp.getString("greet_audio_" + storeKey, ""); }
    public void setAudio(String storeKey, String json) {
        sp.edit().putString("greet_audio_" + storeKey, json).apply();
    }

    // ---------- 主桌面音乐卡片 ----------

    /** 主桌面音乐卡片代发（云听等源不上报总线曲目时补 MediaSession 元数据）。默认开。 */
    public boolean musicCard() { return sp.getBoolean("music_card", true); }
    public void setMusicCard(boolean on) { sp.edit().putBoolean("music_card", on).apply(); }

    // ---------- 盲区侧摄拉直（鱼眼矫正） ----------

    /** 侧摄桶形畸变校正强度 k1：0=关，越大车身拉得越直（过大四角发黑）。 */
    public float blindBend() { return sp.getFloat("blind_bend", 0.20f); }
    public void setBlindBend(float v) { sp.edit().putFloat("blind_bend", v).apply(); }

    /** 矫正后放大倍率：把校正露出的黑边裁掉。 */
    public float blindZoom() { return sp.getFloat("blind_zoom", 1.15f); }
    public void setBlindZoom(float v) { sp.edit().putFloat("blind_zoom", v).apply(); }

    /** 界面整体缩放（顶栏「调整大小」键）。 */
    public float uiScale() { return sp.getFloat("ui_scale", 1.0f); }
    public void setUiScale(float v) { sp.edit().putFloat("ui_scale", v).apply(); }
}
