package com.jietu.clustercast;

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 主桌面音乐卡片代发：launcher 的 MediaCard 只吃 VDBus 模块 6 的 VDMediaItem
 * （393218，getMediaInfo 里是歌名/歌手），mediacenter 的云听源不上报这条事件，
 * 卡片就一直空白。这里盯 MusicListener 的活动会话：总线上的曲目和正在播的
 * 对不上（云听时总线往往是空的），就用 MediaSession 元数据代发一条
 * VDMediaItem + VDMediaPlayTime，云听的歌名/歌手就能出现在原车主桌面卡片上。
 * 总线上曲目一致（说明有人正常发布，如网易云/本地）就完全不写，不抢 mediacenter 的话。
 */
public class MusicCardPublisher {
    private static final String TAG = "ClusterCast";
    private static final long INTERVAL_MS = 1000;

    private static final String ITEM_CLS =
            "com.desaysv.ivi.vdb.event.id.media.bean.VDMediaItem";
    private static final String INFO_CLS =
            "com.desaysv.ivi.vdb.event.id.media.bean.VDMediaInfo";
    private static final String TYPE_CLS =
            "com.desaysv.ivi.vdb.event.id.media.bean.VDMediaType";
    private static final String TIME_CLS =
            "com.desaysv.ivi.vdb.event.id.media.bean.VDMediaPlayTime";

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean running;
    private String lastTitle;
    private String lastErr;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                step();
            } catch (Throwable t) {
                Log.w(TAG, "MusicCardPublisher step: " + t);
            }
            main.postDelayed(this, INTERVAL_MS);
        }
    };

    public synchronized void start() {
        if (running) return;
        running = true;
        main.removeCallbacks(tick);
        main.postDelayed(tick, INTERVAL_MS);
    }

    public synchronized void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    private void step() {
        if (!running) return;
        MediaController c = MusicListener.ready() ? MusicListener.inst().active() : null;
        MediaMetadata md = MusicListener.meta(c);
        String title = md != null ? md.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
        if (title == null || title.length() == 0) return;
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        String album = md.getString(MediaMetadata.METADATA_KEY_ALBUM);

        Vd v = Vd.inst();
        if (v == null || !v.ok()) return;

        try {
            Class<?> itemCls = v.loadBeanClass(ITEM_CLS);
            Class<?> infoCls = v.loadBeanClass(INFO_CLS);
            Object cur = v.readBean(Vd.EV_MEDIA_ITEM, itemCls);
            String busTitle = null;
            int type = -1;
            if (cur != null) {
                type = (Integer) itemCls.getMethod("getMediaType").invoke(cur);
                Object info = itemCls.getMethod("getMediaInfo").invoke(cur);
                if (info != null) {
                    busTitle = (String) infoCls.getMethod("getTitle").invoke(info);
                }
            }
            if (type < 0) {
                // 总线上没有曲目时音源类型从 VDMediaType/VDMediaCard 兜底
                try {
                    Object t = v.readBean(Vd.EV_MEDIA_TYPE, v.loadBeanClass(TYPE_CLS));
                    if (t != null) type = (Integer) t.getClass()
                            .getMethod("getMediaType").invoke(t);
                } catch (Throwable ignored) { }
                if (type < 0) {
                    try {
                        Object card = v.readBean(Vd.EV_MEDIA_CARD,
                                v.loadBeanClass("com.desaysv.ivi.vdb.event.id.media.bean.VDMediaCard"));
                        if (card != null) type = (Integer) card.getClass()
                                .getMethod("getMediaType").invoke(card);
                    } catch (Throwable ignored) { }
                }
                if (type < 0) type = 0;
            }
            if (busTitle != null && busTitle.equals(title)) {
                lastTitle = title;
                lastErr = null;
                return; // 有人正常发布，不抢
            }

            Object info = infoCls.newInstance();
            infoCls.getMethod("putMediaType", int.class).invoke(info, type);
            infoCls.getMethod("putTitle", String.class).invoke(info, title);
            if (artist != null) {
                infoCls.getMethod("putArtist", String.class).invoke(info, artist);
            }
            if (album != null) {
                infoCls.getMethod("putAlbum", String.class).invoke(info, album);
            }
            Object item = itemCls.newInstance();
            itemCls.getMethod("putMediaType", int.class).invoke(item, type);
            itemCls.getMethod("putMediaInfo", infoCls).invoke(item, info);
            String err = v.publishBean(Vd.EV_MEDIA_ITEM, itemCls, item);

            // 回读验证：bus.set 不报错不代表写进去了（实车抓到"代发 ok 但 getOnce=null"）
            String back = null;
            try {
                Object chk = v.readBean(Vd.EV_MEDIA_ITEM, itemCls);
                if (chk != null) {
                    Object ci = itemCls.getMethod("getMediaInfo").invoke(chk);
                    back = ci != null ? (String) infoCls.getMethod("getTitle").invoke(ci) : null;
                }
            } catch (Throwable ignored) { }

            // 进度一起发，卡片的进度条才有东西走
            try {
                Class<?> timeCls = v.loadBeanClass(TIME_CLS);
                Object time = timeCls.newInstance();
                timeCls.getMethod("putMediaType", int.class).invoke(time, type);
                long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                timeCls.getMethod("putDuration", int.class).invoke(time, (int) Math.max(0, dur));
                timeCls.getMethod("putPosition", int.class).invoke(time,
                        (int) Math.max(0, MusicListener.position(c)));
                v.publishBean(Vd.EV_MEDIA_PLAY_TIME, timeCls, time);
            } catch (Throwable ignored) { }

            boolean logIt = !title.equals(lastTitle) || (err != null && !err.equals(lastErr));
            if (logIt) {
                Log.i(TAG, "主桌面音乐卡片代发 type=" + type + " busTitle=" + busTitle
                        + " -> " + title + " / " + artist
                        + (err != null ? " err=" + err : " ok")
                        + " 回读=" + (back == null ? "null（set 没落住）" : back));
            }
            lastTitle = title;
            lastErr = err;
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
            if (!msg.equals(lastErr)) {
                Log.w(TAG, "音乐卡片代发失败 " + title + ": " + t);
                lastErr = msg;
            }
        }
    }
}
