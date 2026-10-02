package com.ahui.clustercast

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * 通知使用权服务：只为拿到当前正在播放的 MediaSession。
 * 授权一条命令（重启不失效，运行期不需要 root）：
 *   adb shell cmd notification allow_listener com.ahui.clustercast/.MusicListener
 */
class MusicListener : NotificationListenerService() {

    override fun onListenerConnected() {
        sInst = this
        Log.i(Caster.TAG, "notification listener connected")
    }

    override fun onListenerDisconnected() {
        sInst = null
    }

    /**
     * 当前正在出声（或最后出声）的那个媒体会话。
     * 只认带歌名的会话：车机上有常驻会话一直上报 STATE_PLAYING 却不给元数据
     * （如 com.bytedance.byteautoservice3），按状态先挑会选中它，真正在放歌的那个反而被跳过。
     */
    fun active(): MediaController? {
        val l = sInst ?: return null
        return try {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val cs = msm.getActiveSessions(ComponentName(l, MusicListener::class.java))
                    ?: return null
            if (cs.isEmpty()) return null
            var titled: MediaController? = null
            for (c in cs) {
                if (!hasTitle(c)) continue
                if (titled == null) titled = c
                val ps = c.playbackState
                if (ps != null && (ps.state == PlaybackState.STATE_PLAYING ||
                                ps.state == PlaybackState.STATE_BUFFERING)) return c
            }
            titled ?: cs[0]
        } catch (t: Throwable) {
            Log.w(Caster.TAG, "getActiveSessions failed: $t")
            null
        }
    }

    companion object {
        private var sInst: MusicListener? = null

        fun inst(): MusicListener? = sInst

        /** 需要「通知使用权」，没给授权时 getActiveSessions 会抛 SecurityException。 */
        fun ready(): Boolean = sInst != null

        private fun hasTitle(c: MediaController): Boolean {
            val md = c.metadata ?: return false
            return md.getString(MediaMetadata.METADATA_KEY_TITLE) != null
        }

        /** 播放位置要自己按时间戳推算，PlaybackState.getPosition() 只是最后一次上报值。 */
        @JvmStatic
        fun position(c: MediaController?): Long {
            if (c == null || c.metadata == null) return 0
            val ps = c.playbackState ?: return 0
            var pos = ps.position
            if (ps.state == PlaybackState.STATE_PLAYING) {
                pos += System.currentTimeMillis() - ps.lastPositionUpdateTime
            }
            val dur = c.metadata!!.getLong(MediaMetadata.METADATA_KEY_DURATION)
            return if (dur > 0 && pos > dur) dur else pos
        }

        @JvmStatic
        fun meta(c: MediaController?): MediaMetadata? = c?.metadata
    }
}
