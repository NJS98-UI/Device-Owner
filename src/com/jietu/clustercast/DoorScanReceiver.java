package com.jietu.clustercast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.kooo.evcam.AppLog;

import java.io.FileWriter;

/**
 * adb 触发的车门 cmdId 扫描器（实车一次性标定用，不进任何界面）。
 *   扫描：adb shell am broadcast -n com.jietu.clustercast/.DoorScanReceiver --es mode start
 *   停止：… --es mode stop
 *   写配置：… --es mode set --ei door 0 --ei cmdid 55
 *   回读：… --es mode read
 * start 后轮询 327681 模块 cmdId 1..128，值有变化就记录（tag=DoorScan，
 * 格式 "CHG cmdId=X a->b"），从未读到过值的信号自动跳过。
 * 结果同时写 /sdcard/door_scan.log —— 这台 ROM 丢 DEBUG 级 logcat，
 * 文件是 adb 侧最可靠的读数通道。
 */
public class DoorScanReceiver extends BroadcastReceiver {
    private static final String TAG = "DoorScan";
    /** 车身 327681（尾门 92）+ 车门模块 327684（15/16/17/18，实车抓包标定）。 */
    private static final int[] EVS = {327681, 327684};
    private static final int MAX_CMD = 192;
    private static final String OUT = "/sdcard/door_scan.log";

    private static volatile boolean sRun;
    private static Thread sThread;

    @Override public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        String mode = intent.getStringExtra("mode");
        if ("start".equals(mode)) {
            startScan(app);
        } else if ("stop".equals(mode)) {
            sRun = false;
            say("扫描停止指令已收到");
        } else if ("set".equals(mode)) {
            int door = intent.getIntExtra("door", -1);
            int cmdId = intent.getIntExtra("cmdid", -1);
            if (door >= 0 && door < DoorGreeting.DOOR_NAMES.length && cmdId > 0) {
                new Cfg(app).setDoorCmdId(door, cmdId);
                say("已写入 " + DoorGreeting.DOOR_NAMES[door] + " cmdId=" + cmdId);
            } else {
                say("set 参数无效 door=" + door + " cmdId=" + cmdId);
            }
        } else if ("read".equals(mode)) {
            Cfg cfg = new Cfg(app);
            StringBuilder sb = new StringBuilder("当前配置:");
            for (int d = 0; d < DoorGreeting.DOOR_NAMES.length; d++) {
                sb.append(' ').append(DoorGreeting.DOOR_NAMES[d]).append('=').append(cfg.doorCmdId(d));
            }
            say(sb.toString());
        } else if ("mediadump".equals(mode)) {
            final boolean loop = intent.getBooleanExtra("loop", false);
            new Thread(new Runnable() { @Override public void run() { mediaDump(app, loop); } },
                    "media-dump").start();
        }
    }

    /**
     * 抓 VDBus 模块 6 媒体事件（launcher 主桌面音乐卡片的数据源）。
     * 播放网易云/云听时各跑一次，对比谁往总线上发了什么：
     *   adb shell am broadcast -n com.jietu.clustercast/.DoorScanReceiver --es mode mediadump
     */
    private static void mediaDump(Context app, boolean loop) {
        Vd v = Vd.connect(app);
        if (!v.ok()) {
            say("mediadump 总线没连上 " + v.bindReport);
            return;
        }
        final int[][] EVS = {
                {Vd.EV_MEDIA_TYPE, 0}, {Vd.EV_MEDIA_ITEM, 0}, {Vd.EV_MEDIA_EXT_INFO, 0},
                {Vd.EV_MEDIA_CARD, 0}, {Vd.EV_MEDIA_PLAY_TIME, 0},
        };
        String[] NAMES = {"TYPE", "ITEM", "EXT", "CARD", "TIME"};
        int rounds = loop ? 10 : 1;
        for (int r = 0; r < rounds; r++) {
            for (int i = 0; i < EVS.length; i++) {
                try {
                    Class<?>[] beans = {
                            cls(v, "VDMediaType"), cls(v, "VDMediaItem"), cls(v, "VDMediaExtInfo"),
                            cls(v, "VDMediaCard"), cls(v, "VDMediaPlayTime"),
                    };
                    Object bean = v.readBean(EVS[i][0], beans[i]);
                    say("MEDIA " + NAMES[i] + " ev=" + EVS[i][0] + " = " + bean);
                } catch (Throwable t) {
                    say("MEDIA " + NAMES[i] + " ev=" + EVS[i][0] + " 读失败 " + t);
                }
            }
            if (r < rounds - 1) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }
        android.media.session.MediaController c = MusicListener.ready()
                ? MusicListener.inst().active() : null;
        if (c != null) {
            android.media.MediaMetadata md = c.getMetadata();
            say("MEDIA SESSION pkg=" + c.getPackageName()
                    + " title=" + (md != null ? md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) : null)
                    + " artist=" + (md != null ? md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) : null));
            dumpMetaKeys(md);
        } else {
            say("MEDIA SESSION 无活动会话（通知使用权未授权或没在播）");
        }
    }

    /** 枚举活动会话元数据的全部 key（找歌词/封面类自定义字段，keySet 是隐藏 API 走反射）。 */
    private static void dumpMetaKeys(android.media.MediaMetadata md) {
        if (md == null) { say("META KEYS metadata=null"); return; }
        try {
            java.lang.reflect.Method ks = android.media.MediaMetadata.class
                    .getMethod("keySet");
            @SuppressWarnings("unchecked")
            java.util.Set<String> keys = (java.util.Set<String>) ks.invoke(md);
            for (String k : keys) {
                String val = md.getString(k);
                if (val != null && val.length() > 120) val = val.substring(0, 120) + "…";
                say("META KEY " + k + " = " + val);
            }
            return;
        } catch (Throwable t) {
            say("META KEYS keySet 不可用 " + t);
        }
        String[] cand = {"android.media.metadata.LYRICS", "lyrics", "LYRICS",
                "android.media.metadata.COMPOSER", "android.media.metadata.GENRE"};
        for (String k : cand) {
            if (md.containsKey(k)) say("META KEY " + k + " = " + md.getString(k));
        }
    }

    private static Class<?> cls(Vd v, String simple) throws Exception {
        return v.loadBeanClass("com.desaysv.ivi.vdb.event.id.media.bean." + simple);
    }

    /** logcat（INFO 级）+ /sdcard/door_scan.log 双通道输出。 */
    private static void say(String msg) {
        AppLog.i(TAG, msg);
        try {
            FileWriter w = new FileWriter(OUT, true);
            w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date()) + " " + msg + "\n");
            w.close();
        } catch (Throwable ignored) { }
    }

    private static void startScan(final Context app) {
        if (sThread != null && sThread.isAlive()) {
            say("扫描已在进行");
            return;
        }
        sRun = true;
        sThread = new Thread(new Runnable() { @Override public void run() { scan(app); } }, "door-scan");
        sThread.start();
    }

    private static void scan(Context app) {
        say("车门 cmdId 扫描开始（" + EVS[0] + "/" + EVS[1] + ", 1.." + MAX_CMD + "）");
        Vd v = Vd.connect(app);
        if (!v.ok()) {
            say("总线没连上 " + v.bindReport);
            return;
        }
        int[][] prev = new int[EVS.length][MAX_CMD + 1];
        int[][] miss = new int[EVS.length][MAX_CMD + 1];
        for (int e = 0; e < EVS.length; e++) java.util.Arrays.fill(prev[e], -1);
        for (int e = 0; e < EVS.length; e++) {
            StringBuilder snap = new StringBuilder("SNAP ev=" + EVS[e]);
            for (int id = 1; id <= MAX_CMD; id++) {
                int val = v.getItem(EVS[e], id);
                prev[e][id] = val;
                if (val >= 0) snap.append(' ').append(id).append('=').append(val);
            }
            say(snap.toString());
        }
        long deadline = System.currentTimeMillis() + 5 * 60_000L;
        while (sRun && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            if (!v.ok()) { Vd.connect(app); continue; }
            for (int e = 0; e < EVS.length; e++) {
                for (int id = 1; id <= MAX_CMD; id++) {
                    if (miss[e][id] > 10) continue; // 从没读到过，别再耗 binder
                    int now = v.getItem(EVS[e], id);
                    if (now < 0) { miss[e][id]++; prev[e][id] = -1; continue; }
                    if (prev[e][id] >= 0 && prev[e][id] != now) {
                        say("CHG ev=" + EVS[e] + " cmdId=" + id + " " + prev[e][id] + "->" + now);
                    }
                    prev[e][id] = now;
                }
            }
        }
        sRun = false;
        say("车门 cmdId 扫描结束");
    }
}
