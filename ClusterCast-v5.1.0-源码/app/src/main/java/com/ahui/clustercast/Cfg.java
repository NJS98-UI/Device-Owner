package com.ahui.clustercast;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 记住投屏目标、跟随前台、投屏仪表档位、高德禁用状态、管理员弹窗顶回、
 * 投屏整屏填充、沉浸dock、强制顶层，以及后视镜正常位/下翻位（读取→保存）。
 * 「音乐卡片对接」仍是删掉的：取证证明卡片只吃 com.desaysv.mediacenter 自己发的
 * VDB MEDIA 总线，仪表渲染器（pid 4207）根本没订阅那一串。
 * 「沉浸dock」v4.6.3 恢复：dock 其实就是 systemui 画的导航栏，
 * 原车自己的开关是 system 表里的 com.desaysv.status.bar.status（2↔0），
 * 之前「QNX 渲染、控制不了」的结论是错的。
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

    /** 投屏时把仪表切到哪一档：Vd.THEME_SIMPLE(4) 或 Vd.THEME_NAVI(3)。默认极简。 */
    public int castTheme() { return sp.getInt("cast_theme", Vd.THEME_SIMPLE); }
    public void setCastTheme(int t) { sp.edit().putInt("cast_theme", t).apply(); }

    /**
     * 「高德是我们禁的」必须落盘。
     * 否则投屏中途进程被杀，高德就永久停在禁用状态，没人负责恢复。
     */
    public boolean amapOffByUs() { return sp.getBoolean("amap_off_by_us", false); }
    public void setAmapOffByUs(boolean on) { sp.edit().putBoolean("amap_off_by_us", on).apply(); }

    /** 屏蔽「管理员不允许此更改」弹窗（顶回去）。默认开。 */
    public boolean guard() { return sp.getBoolean("guard", true); }
    public void setGuard(boolean on) { sp.edit().putBoolean("guard", on).apply(); }

    /** 投屏整屏填充：launchBounds 由我们写成仪表整屏，不让原车布局留边。默认开。 */
    public boolean castFill() { return sp.getBoolean("cast_fill", true); }
    public void setCastFill(boolean on) { sp.edit().putBoolean("cast_fill", on).apply(); }

    /**
     * 沉浸dock：投屏期间把 com.desaysv.status.bar.status 写成 0（systemui 那排导航栏收起），
     * 退出投屏写回进入前的值。默认开；没授权 WRITE_SECURE_SETTINGS 时写了会照实报失败。
     */
    public boolean dockImmersive() { return sp.getBoolean("dock_imm", true); }
    public void setDockImmersive(boolean on) { sp.edit().putBoolean("dock_imm", on).apply(); }

    /** 强制顶层：仪表页一丢焦点就重新拉回 display 2（带冷却和次数上限）。默认开。 */
    public boolean forceTop() { return sp.getBoolean("force_top", true); }
    public void setForceTop(boolean on) { sp.edit().putBoolean("force_top", on).apply(); }

    /**
     * 总线压制原车导航（CAR_LAN 721699/721702）：防止高德后台吐状态导致仪表渲染「地图信息准备中」盖住投屏。
     * 默认开。用户主动选择投屏原车高德时会自动放行。
     */
    public boolean suppressNaviCluster() { return sp.getBoolean("suppress_navi", true); }
    public void setSuppressNaviCluster(boolean on) { sp.edit().putBoolean("suppress_navi", on).apply(); }

    /**
     * 整屏镜像档（参考开源 ScreenMirro 的做法：MediaProjection 抓主屏 +
     * VirtualDisplay 直接输出到仪表屏上的一块面，零拷贝）。默认关。
     * 开着它，三指左滑投的是「主屏此刻的整屏画面」，语义和普通档不同，
     * 所以这个开关由用户自己点，绝不自作主张打开。
     */
    public boolean mirrorMode() { return sp.getBoolean("mirror_mode", true); }
    public void setMirrorMode(boolean on) { sp.edit().putBoolean("mirror_mode", on).apply(); }

    // ---------- 倒车后视镜自动下翻（读取位置→保存；挂 R 写下翻位，退 R 写回正常位） ----------

    /** 挂 R 自动下翻总开关。没保存过位置服务不会乱写，默认关。 */
    public boolean mirrorDip() { return sp.getBoolean("mdip", false); }
    public void setMirrorDip(boolean on) { sp.edit().putBoolean("mdip", on).apply(); }

    /**
     * 用户保存的两个镜位（327681/201 左、218 右 的总线读数）：
     * 正常位 = 平时用的角度；下翻位 = 倒车想看地面的角度。
     * -1 = 还没保存过。取证：这两条在包里读得到（log:108380/108385 values=[1]）。
     */
    public int mirrorNormalL() { return sp.getInt("mn_l", -1); }
    public int mirrorNormalR() { return sp.getInt("mn_r", -1); }
    public void setMirrorNormal(int l, int r) {
        sp.edit().putInt("mn_l", l).putInt("mn_r", r).apply();
    }

    public int mirrorDownL() { return sp.getInt("md_l", -1); }
    public int mirrorDownR() { return sp.getInt("md_r", -1); }
    public void setMirrorDown(int l, int r) {
        sp.edit().putInt("md_l", l).putInt("md_r", r).apply();
    }

    /** 两个镜位都保存过才允许开自动下翻。 */
    public boolean mirrorReady() { return mirrorDownL() >= 0 && mirrorDownR() >= 0; }
    public boolean mirrorNormalReady() { return mirrorNormalL() >= 0 && mirrorNormalR() >= 0; }

    // ---------- 记录仪 ----------

    /** 循环分段时长（分钟）：3 / 5 / 10，默认 5。 */
    public int loopMin() { return sp.getInt("loop_min", 5); }
    public void setLoopMin(int m) { sp.edit().putInt("loop_min", m).apply(); }

    /**
     * 录像带声音（盯盯车同款 AAC 音轨）。默认开；麦克风被原车占用时 QuadRec
     * 只在日志写真实原因，画面照常录 —— 关了就不碰麦克风。
     */
    public boolean dvrSound() { return sp.getBoolean("dvr_sound", true); }
    public void setDvrSound(boolean on) { sp.edit().putBoolean("dvr_sound", on).apply(); }

    /** 录像水印：时间/车速/档位烧进画面（真实来源，取不到画 "--"）。默认开。 */
    public boolean dvrWatermark() { return sp.getBoolean("dvr_wm", true); }
    public void setDvrWatermark(boolean on) { sp.edit().putBoolean("dvr_wm", on).apply(); }

    /**
     * 挡位自动录（盯盯车 auto_record_on_r_gear / d_gear 的自写逻辑）：
     * 挂入 R/D 自动开录，挂入 P 自动收尾。默认关 —— 这支要用户自己点开。
     */
    public boolean dvrAutoGear() { return sp.getBoolean("dvr_gear", false); }
    public void setDvrAutoGear(boolean on) { sp.edit().putBoolean("dvr_gear", on).apply(); }

    /**
     * 画质档（盯盯车 bitrate_level 低/标准/高）：0=低 1=标准 2=高，默认标准。
     * 换算成码率是 CamCtl 的事（4/8/12 Mbps）。
     */
    public int dvrQuality() { return sp.getInt("dvr_q", 1); }
    public void setDvrQuality(int q) { sp.edit().putInt("dvr_q", q).apply(); }

    /** 紧急自动锁定（盯盯车 emergency_detection_enabled）：加速度计超阈值把在录段锁进 锁定 目录。默认开。 */
    public boolean dvrEmergency() { return sp.getBoolean("dvr_emg", true); }
    public void setDvrEmergency(boolean on) { sp.edit().putBoolean("dvr_emg", on).apply(); }

    /** 触发阈值，单位 m/s²（扣除重力后的速度突变）。默认 18 ≈ 1.8g。 */
    public float dvrEmergencyThreshold() { return sp.getFloat("dvr_emg_t", 18f); }

    /**
     * 画面调节（盯盯车 saturation / 对比度 / 亮度那套的自写版）：
     * 0=标准 1=明亮 2=鲜艳 3=柔和。系数表在 CamCtl.PICTURE，默认标准 = 不加处理。
     */
    public int dvrPicture() { return sp.getInt("dvr_pic", 0); }
    public void setDvrPicture(int v) { sp.edit().putInt("dvr_pic", v).apply(); }

    /** 开机自动录像（盯盯车 auto_start_recording）：服务一起来就开录。默认关。 */
    public boolean dvrAutoBoot() { return sp.getBoolean("dvr_boot", false); }
    public void setDvrAutoBoot(boolean on) { sp.edit().putBoolean("dvr_boot", on).apply(); }

    /** 开录/停录音效（盯盯车录像提示音的自写版）：ToneGenerator，不占麦克风。默认开。 */
    public boolean dvrTone() { return sp.getBoolean("dvr_tone", true); }
    public void setDvrTone(boolean on) { sp.edit().putBoolean("dvr_tone", on).apply(); }

    /**
     * 存储上限（盯盯车 video_storage_limit 的自写版）：录像占的总空间超过这个数
     * 就从最旧一段开始删（锁定目录不动）。0=不限，按分钟循环删照旧。默认 0。
     */
    public int dvrCapMB() { return sp.getInt("dvr_cap", 0); }
    public void setDvrCapMB(int v) { sp.edit().putInt("dvr_cap", v).apply(); }

    // ---------- EVCam 移植的功能开关 ----------

    /**
     * 息屏锁车录制（EVCam screen_off_recording 同款语义）：熄屏延时自动开录、
     * 亮屏延时自动收尾。默认关 —— 这支要用户自己点开。
     */
    public boolean dvrScreenOff() { return sp.getBoolean("dvr_scr", false); }
    public void setDvrScreenOff(boolean on) { sp.edit().putBoolean("dvr_scr", on).apply(); }

    /** 录制状态悬浮按钮（EVCam 悬浮窗快捷入口）：红=没录、绿闪=录制中。默认关。 */
    public boolean dvrFloat() { return sp.getBoolean("dvr_float", false); }
    public void setDvrFloat(boolean on) { sp.edit().putBoolean("dvr_float", on).apply(); }

    /**
     * 参与录制的摄像头掩码（EVCam「录制摄像头选择」）：bit0=前 bit1=后 bit2=左 bit3=右。
     * 没选中的路不参与录像（对应格子黑），预览页照常能看。默认全参录。
     */
    public int dvrMask() { return sp.getInt("dvr_mask", 0xF); }
    public void setDvrMask(int m) { sp.edit().putInt("dvr_mask", m).apply(); }

    /** 照片存储上限（EVCam 照片最大存储空间）：超过从最旧删。0=不限。默认 0。 */
    public int dvrPhotoCapMB() { return sp.getInt("dvr_pcap", 0); }
    public void setDvrPhotoCapMB(int v) { sp.edit().putInt("dvr_pcap", v).apply(); }
}
