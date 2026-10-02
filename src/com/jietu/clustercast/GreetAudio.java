package com.jietu.clustercast;

import org.json.JSONObject;

/**
 * 迎宾语内置音频目录 + 每事件音频配置。
 * 配置完整复刻参考应用（云盘车机助手）单击操作"播放音频"的参数集：
 * 通道(AudioAttributes usage) / 音频焦点 / 播放时修改音量 / 扬声器输出设备 / 音量增益 / 音源。
 * 音源："raw:资源名"=APK 内置，"file:绝对路径"=用户从本机选的文件，""=未配置(走 TTS)。
 */
public final class GreetAudio {

    public static class Item {
        public final String label;   // 显示名（主驾开 / D档 …）
        public final String name;    // raw 资源名
        public Item(String l, String n) { label = l; name = n; }
    }

    /** 内置音频（与 res/raw 一一对应）。 */
    public static final Item[] BUILTIN = {
            new Item("主驾开", "greet_main_open"),
            new Item("主驾关", "greet_main_close"),
            new Item("副驾开", "greet_copilot_open"),
            new Item("副驾关", "greet_copilot_close"),
            new Item("左后门开", "greet_left_open"),
            new Item("左后门关", "greet_left_close"),
            new Item("右后门开", "greet_right_open"),
            new Item("右后门关", "greet_right_close"),
            new Item("后备箱开", "greet_tail_open"),
            new Item("后备箱关", "greet_tail_close"),
            new Item("P档", "greet_gear_p"),
            new Item("R档", "greet_gear_r"),
            new Item("N档", "greet_gear_n"),
            new Item("D档", "greet_gear_d"),
            new Item("发动机启动", "greet_engine_start"),
            new Item("发动机熄火", "greet_engine_stop"),
    };

    public static Item builtin(String name) {
        for (Item it : BUILTIN) if (it.name.equals(name)) return it;
        return null;
    }

    /** 播放参数集，默认值（通道4=语音辅助、暂停其它音频、不改音量、默认扬声器、0 增益）。 */
    public static class Cfg {
        public String src = "";
        public int channel = 4;   // AudioAttributes usage
        public int focus = 2;     // -1无 2=播放时暂停其它音频 3=播放时降低其它音量
        public int volType = -1;  // -1不修改；0-11=AudioManager stream
        public int volVal = 80;   // volType>=0 时的目标音量 0-100
        public int speaker = -1;  // -1默认；否则 AudioDeviceInfo.getId()
        public int gain = 0;      // LoudnessEnhancer 目标增益 mB（0-8000）
    }

    public static Cfg parse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            Cfg c = new Cfg();
            c.src = o.optString("src", "");
            c.channel = o.optInt("channel", 1);
            c.focus = o.optInt("focus", 2);
            c.volType = o.optInt("volType", -1);
            c.volVal = o.optInt("volVal", 80);
            c.speaker = o.optInt("speaker", -1);
            c.gain = o.optInt("gain", 0);
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    public static String toJson(Cfg c) {
        try {
            JSONObject o = new JSONObject();
            o.put("src", c.src);
            o.put("channel", c.channel);
            o.put("focus", c.focus);
            o.put("volType", c.volType);
            o.put("volVal", c.volVal);
            o.put("speaker", c.speaker);
            o.put("gain", c.gain);
            return o.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 配置里存的音源解析成可播放对象：raw 资源 id（resId>0）或文件路径。 */
    public static class Src {
        public int resId;      // >0 = 内置 raw
        public String path;    // file 源
    }

    public static Src resolve(android.content.Context ctx, String src) {
        if (src == null || src.isEmpty()) return null;
        Src s = new Src();
        if (src.startsWith("raw:")) {
            Item it = builtin(src.substring(4));
            if (it == null) return null;
            s.resId = ctx.getResources().getIdentifier(it.name, "raw", ctx.getPackageName());
            return s.resId > 0 ? s : null;
        }
        if (src.startsWith("file:")) {
            String p = src.substring(5);
            java.io.File f = new java.io.File(p);
            return f.exists() && f.length() > 0 ? s : null;
        }
        return null;
    }

    /** 给设置页按钮用的短标签。 */
    public static String labelOf(Cfg c) {
        if (c == null || c.src == null || c.src.isEmpty()) return "内置";
        if (c.src.startsWith("raw:")) {
            Item it = builtin(c.src.substring(4));
            return it != null ? it.label : "内置";
        }
        String p = c.src.substring(5);
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private GreetAudio() { }
}
