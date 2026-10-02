package com.jietu.clustercast;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * 通知使用权服务：只为拿到当前正在播放的 MediaSession（仪表盘悬浮音乐数据源）。
 * 授权一条命令（重启不失效，无需 root）：
 *   adb shell cmd notification allow_listener com.jietu.clustercast/.MusicListener
 */
public class MusicListener extends NotificationListenerService {

    private static final String TAG = "ClusterCast";

    @Override
    public void onListenerConnected() {
        sInst = this;
        Log.i(TAG, "notification listener connected");
    }

    @Override
    public void onListenerDisconnected() {
        sInst = null;
    }

    /**
     * 当前正在出声（或最后出声）的那个媒体会话。
     * 只认带歌名的会话：车机上有常驻会话一直上报 STATE_PLAYING 却不给元数据
     * （如 com.bytedance.byteautoservice3），按状态先挑会选中它，真正在放歌的那个反而被跳过。
     */
    public MediaController active() {
        MusicListener l = sInst;
        if (l == null) return null;
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            java.util.List<MediaController> cs = msm.getActiveSessions(
                    new ComponentName(l, MusicListener.class));
            if (cs == null || cs.isEmpty()) return null;
            MediaController titled = null;
            for (MediaController c : cs) {
                if (!hasTitle(c)) continue;
                if (titled == null) titled = c;
                PlaybackState ps = c.getPlaybackState();
                if (ps != null && (ps.getState() == PlaybackState.STATE_PLAYING ||
                        ps.getState() == PlaybackState.STATE_BUFFERING)) return c;
            }
            return titled != null ? titled : cs.get(0);
        } catch (Throwable t) {
            Log.w(TAG, "getActiveSessions failed: " + t);
            return null;
        }
    }

    private static volatile MusicListener sInst = null;

    public static MusicListener inst() { return sInst; }

    /** 需要「通知使用权」，没给授权时 getActiveSessions 会抛 SecurityException。 */
    public static boolean ready() { return sInst != null; }

    private static boolean hasTitle(MediaController c) {
        MediaMetadata md = c.getMetadata();
        return md != null && md.getString(MediaMetadata.METADATA_KEY_TITLE) != null;
    }

    /** 播放位置要自己按时间戳推算，PlaybackState.getPosition() 只是最后一次上报值。 */
    public static long position(MediaController c) {
        if (c == null || c.getMetadata() == null) return 0;
        PlaybackState ps = c.getPlaybackState();
        if (ps == null) return 0;
        long pos = ps.getPosition();
        if (ps.getState() == PlaybackState.STATE_PLAYING) {
            pos += System.currentTimeMillis() - ps.getLastPositionUpdateTime();
        }
        long dur = c.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION);
        return (dur > 0 && pos > dur) ? dur : pos;
    }

    public static boolean playing(MediaController c) {
        if (c == null) return false;
        PlaybackState ps = c.getPlaybackState();
        if (ps == null) return false;
        return ps.getState() == PlaybackState.STATE_PLAYING ||
                ps.getState() == PlaybackState.STATE_BUFFERING;
    }

    public static MediaMetadata meta(MediaController c) {
        return c != null ? c.getMetadata() : null;
    }
}
