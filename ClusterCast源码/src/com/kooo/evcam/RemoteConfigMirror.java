package com.kooo.evcam;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Properties;

/**
 * 远程查看配置的外部存储镜像。
 * 车机上 /data 分区可能写不进或被系统清掉，SharedPreferences 一旦丢失
 * 远程查看/电报/飞书配置就全没了。每次保存配置时同步镜像一份到
 * /sdcard/Android/data/<pkg>/files/config_backup/，加载时 prefs 为空
 * 就从这里恢复。Properties 用 Unicode 转义存中文/特殊字符，往返无损。
 */
public final class RemoteConfigMirror {

    private static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "config_backup");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static void mirror(Context c, String prefName, Map<String, String> kv) {
        try {
            // 增量合并：单键更新（如 bot_api_host）不能把其他键从备份里抹掉
            Properties p = read(c, prefName);
            if (p == null) p = new Properties();
            p.putAll(kv);
            FileOutputStream fo = new FileOutputStream(new File(dir(c), prefName + ".props"));
            p.store(fo, null);
            fo.close();
        } catch (Throwable ignored) { }
    }

    public static Properties read(Context c, String prefName) {
        try {
            File f = new File(dir(c), prefName + ".props");
            if (!f.exists()) return null;
            Properties p = new Properties();
            FileInputStream fi = new FileInputStream(f);
            p.load(fi);
            fi.close();
            return p.isEmpty() ? null : p;
        } catch (Throwable t) {
            return null;
        }
    }

    private RemoteConfigMirror() { }
}
