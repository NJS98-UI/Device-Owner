package com.ahui.clustercast;

import android.content.Context;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 录像/照片的存放位置：优先外置 USB，其次公共 Download，最后应用私有目录。
 *
 * 抓包时车上没插 U 盘（live_1920 里只有 /storage/emulated/0），所以挂载点
 * 全名不能照抄文档，只能在运行时问 StorageManager：removable 且有写权限的
 * 卷才算 USB。没插就明说「没检测到 U 盘」并退回内部目录，绝不假装写到 USB。
 */
public final class Storage {

    private Storage() { }

    /** 一句话说明当前落在哪个存储上（含剩余空间），界面和日志共用。 */
    public static String describe(Context c) {
        Spot d = pick(c);
        long free = d.first.getUsableSpace() / (1024 * 1024);
        return d.first.getAbsolutePath() + "（剩余 " + free + "MB）" +
                (d.usb ? "" : "；没检测到可写 U 盘，先存车里");
    }

    static class Spot {
        final File first;
        final boolean usb;
        Spot(File f, boolean u) { first = f; usb = u; }
    }

    /** 选存储根目录，并建好 ClusterCast/环视 子目录。 */
    public static File dir(Context c) {
        Spot s = pick(c);
        File d = new File(s.first, "ClusterCast");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static Spot pick(Context c) {
        File u = usbRoot(c);
        if (u != null) return new Spot(u, true);
        File ext = c.getExternalFilesDir(null);
        if (ext != null && ext.canWrite()) {
            File p = ext.getParentFile();
            return new Spot(p != null ? p : ext, false);
        }
        File i = c.getFilesDir().getParentFile();
        return new Spot(i != null ? i : c.getFilesDir(), false);
    }

    /**
     * 找可移动存储（U 盘）：遍历 StorageManager 的卷，removable=true 且能拿到
     * 可写路径才算。Android 11 下普通应用只能写卷上自己的包目录，这够用。
     */
    private static File usbRoot(Context c) {
        try {
            StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
            List<StorageVolume> vols = sm.getStorageVolumes();
            for (StorageVolume v : vols) {
                if (!v.isRemovable()) continue;
                File f = null;
                try {
                    Object path = v.getClass().getMethod("getPath").invoke(v);
                    if (path instanceof String) f = new File((String) path);
                } catch (Throwable t) {
                    f = null;
                }
                if (f != null && f.isDirectory() && f.canWrite()) return f;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 循环录像的清理：录满就删最旧一段，直到腾出 wantBytes。
     * 只删我们自己建的 .mp4，别的文件一个不碰。
     */
    public static int makeRoom(File d, long wantBytes) {
        File[] all = d.listFiles();
        if (all == null) return 0;
        ArrayList<File> vids = new ArrayList<>();
        for (File f : all)
            if (f.isFile() && f.getName().endsWith(".mp4")) vids.add(f);
        Collections.sort(vids, ByTime.INSTANCE);
        int deleted = 0;
        for (File f : vids) {
            if (d.getUsableSpace() > wantBytes) break;
            if (f.delete()) deleted++;
        }
        return deleted;
    }

    /** 按最后修改时间升序（最旧的在前）。 */
    private enum ByTime implements Comparator<File> {
        INSTANCE;
        @Override public int compare(File a, File b) {
            return Long.compare(a.lastModified(), b.lastModified());
        }
    }
}
