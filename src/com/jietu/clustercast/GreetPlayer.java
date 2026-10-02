package com.jietu.clustercast;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;

import com.kooo.evcam.AppLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 迎宾语播放引擎，完整复刻参考应用（云盘车机助手）单击操作"播放音频"的执行行为：
 *   · 通道：AudioAttributes.setUsage(channel)，content 固定 MUSIC
 *   · 音频焦点：2=瞬时抢占 3=瞬时压低；熄火后原车会拒焦点/抢焦点——拒绝照播，
 *     只有 AUDIOFOCUS_LOSS（永久丢失）才停（实车 2026-10-01 日志实锤）
 *   · 播放时修改系统音量（stream 0-11），播完自动还原
 *   · 扬声器：setPreferredDevice 指定输出设备（API 23+）
 *   · 音量增益：LoudnessEnhancer，目标增益 = progress*100 mB（API 19+）
 *   · .pcm 走 AudioTrack(44100/单声道/16bit)，其余走 MediaPlayer；播完回调并清理
 * 熄火休眠后 audio 服务可能假死：requestAudioFocus/setStreamVolume 这类 binder
 * 调用一旦挂住，绝不能持有类锁 —— 所以 STATE 锁内只做引用交换，binder 全在锁外，
 * 单次卡死只废掉那一个播放线程，后续播报照常（用户实车踩到的熄火后全哑）。
 */
public final class GreetPlayer {

    private static final Object STATE = new Object();
    private static MediaPlayer sMp;
    private static AudioTrack sTrack;
    private static LoudnessEnhancer sEnh;
    private static AudioManager sAm;
    private static AudioFocusRequest sFoc26;
    private static AudioManager.OnAudioFocusChangeListener sFocOld;
    private static int sVolType = -1, sVolOld = -1;
    private static Thread sPcm;
    private static volatile boolean sCancelled;

    public interface Done { void onDone(); }

    public static void stop() {
        Thread t;
        MediaPlayer mp;
        AudioTrack tr;
        LoudnessEnhancer en;
        AudioManager am;
        AudioFocusRequest f26;
        AudioManager.OnAudioFocusChangeListener fol;
        int vt, vo;
        synchronized (STATE) {
            sCancelled = true;
            t = sPcm; sPcm = null;
            mp = sMp; sMp = null;
            tr = sTrack; sTrack = null;
            en = sEnh; sEnh = null;
            am = sAm;
            f26 = sFoc26; sFoc26 = null;
            fol = sFocOld; sFocOld = null;
            vt = sVolType; vo = sVolOld;
            sVolType = -1; sVolOld = -1;
        }
        if (t != null) t.interrupt();
        restoreVolume(am, vt, vo);
        abandonFocus(am, f26, fol);
        if (en != null) {
            try { en.release(); } catch (Throwable ignored) { }
        }
        if (mp != null) {
            try { mp.stop(); } catch (Throwable ignored) { }
            try { mp.release(); } catch (Throwable ignored) { }
        }
        if (tr != null) {
            try { tr.stop(); } catch (Throwable ignored) { }
            try { tr.flush(); } catch (Throwable ignored) { }
            try { tr.release(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 播一条。rawRes>0 用内置 raw，否则读 filePath。播完（或出错）回调 onDone。
     * 返回 false=没播起来（焦点没拿到/打开失败）。
     */
    public static boolean play(Context ctx, GreetAudio.Cfg c, int rawRes,
                               String filePath, final Done done) {
        stop();
        sCancelled = false;
        final AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        synchronized (STATE) { sAm = am; }
        // 实车实锤（2026-10-01 日志）：熄火后原车 CarAudioFocus 会 REQUEST_FAILED
        // 拒我们、或立刻抢走瞬时焦点。播报是短语音，焦点失败/被抢都不能拦——照播。
        requestFocus(am, c.focus);
        // 返回值只用来记日志；拒绝也照播
        // （requestFocus 内部拿不到焦点时已有 stop 监听，永久丢焦点才停）
        if (c.volType >= 0) {
            try {
                int old = am.getStreamVolume(c.volType);
                am.setStreamVolume(c.volType, c.volVal, 0);
                synchronized (STATE) { sVolType = c.volType; sVolOld = old; }
            } catch (Throwable t) {
                synchronized (STATE) { sVolType = -1; sVolOld = -1; }
            }
        }
        // 自动响度归一：不同来源音频响度差很多，轻的升、响的降，对齐到同一响度
        final int autoGain = GreetLoudness.autoGainMb(ctx, rawRes, filePath);
        boolean ok = filePath != null && filePath.toLowerCase().endsWith(".pcm")
                ? playPcm(c, filePath, autoGain, done)
                : playMedia(ctx, c, rawRes, filePath, autoGain, done);
        if (!ok) {
            synchronized (STATE) {
                restoreVolume(am, sVolType, sVolOld);
                sVolType = -1; sVolOld = -1;
                abandonFocus(am, sFoc26, sFocOld);
                sFoc26 = null; sFocOld = null;
            }
        }
        return ok;
    }

    private static boolean playMedia(Context ctx, GreetAudio.Cfg c, int rawRes,
                                     String filePath, int autoGain, final Done done) {
        try {
            MediaPlayer mp = new MediaPlayer();
            synchronized (STATE) {
                if (sCancelled) { try { mp.release(); } catch (Throwable ignored) { } return false; }
                sMp = mp;
            }
            AudioAttributes.Builder ab = new AudioAttributes.Builder()
                    .setUsage(c.channel)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
            mp.setAudioAttributes(ab.build());
            if (c.speaker >= 0) setPreferredDevice(am(), c.speaker, null, mp);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) {
                    applyGain(m.getAudioSessionId(), c.gain + autoGain);
                    synchronized (STATE) {
                        if (sCancelled || sMp != m) return;
                        try { m.start(); } catch (Throwable t) { finish(done); }
                    }
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) { finish(done); }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    AppLog.w(TAG, "播放出错 what=" + what + " extra=" + extra);
                    finish(done);
                    return true;
                }
            });
            if (rawRes > 0) {
                android.content.res.AssetFileDescriptor fd = ctx.getResources()
                        .openRawResourceFd(rawRes);
                mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                fd.close();
            } else {
                mp.setDataSource(filePath);
            }
            mp.prepareAsync();
            return true;
        } catch (Throwable t) {
            AppLog.w(TAG, "起播失败: " + t);
            synchronized (STATE) {
                if (sMp != null) { try { sMp.release(); } catch (Throwable ignored) { } sMp = null; }
            }
            return false;
        }
    }

    private static boolean playPcm(GreetAudio.Cfg c, String filePath, int autoGain, final Done done) {
        try {
            int buf = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(c.channel)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            AudioFormat af = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
            AudioTrack track = new AudioTrack(aa, af, buf, AudioTrack.MODE_STREAM, 0);
            synchronized (STATE) {
                if (sCancelled) { try { track.release(); } catch (Throwable ignored) { } return false; }
                sTrack = track;
            }
            if (c.speaker >= 0) setPreferredDevice(am(), c.speaker, track, null);
            applyGain(track.getAudioSessionId(), c.gain + autoGain);
            track.play();
            final File f = new File(filePath);
            final GreetAudio.Cfg cfg = c;
            final AudioTrack myTrack = track;
            Thread pcm = new Thread(new Runnable() { @Override public void run() {
                InputStream in = null;
                try {
                    in = new FileInputStream(f);
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0 && !sCancelled) {
                        synchronized (STATE) {
                            // 只写自己那一播的 track：新一轮 play 已换 track 就退出
                            if (sTrack != myTrack) return;
                            myTrack.write(b, 0, n);
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) { }
                    if (sTrack == myTrack && !sCancelled) finish(done);
                }
            }}, "greet-pcm");
            synchronized (STATE) { sPcm = pcm; }
            pcm.start();
            return true;
        } catch (Throwable t) {
            AppLog.w(TAG, "PCM 起播失败: " + t);
            synchronized (STATE) {
                if (sTrack != null) { try { sTrack.release(); } catch (Throwable ignored) { } sTrack = null; }
            }
            return false;
        }
    }

    private static AudioManager am() {
        synchronized (STATE) { return sAm; }
    }

    private static void finish(final Done done) {
        stop();
        if (done != null) done.onDone();
    }

    private static void applyGain(int sessionId, int gainMb) {
        if (gainMb <= 0) return;
        try {
            LoudnessEnhancer e = new LoudnessEnhancer(sessionId);
            e.setTargetGain(gainMb);
            e.setEnabled(true);
            synchronized (STATE) { sEnh = e; }
        } catch (Throwable ignored) { }
    }

    private static void setPreferredDevice(AudioManager am, int speakerId,
                                           AudioTrack track, MediaPlayer mp) {
        try {
            if (am == null) return;
            AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo d : devs) {
                if (d.getId() == speakerId) {
                    if (track != null) track.setPreferredDevice(d);
                    else if (mp != null) mp.setPreferredDevice(d);
                    return;
                }
            }
        } catch (Throwable ignored) { }
    }

    private static boolean requestFocus(AudioManager am, int type) {
        if (type < 0) return true;
        try {
            AudioManager.OnAudioFocusChangeListener l = new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int loss) {
                    // 只有永久丢失才停；LOSS_TRANSIENT/CAN_DUCK 忽略——
                    // 熄火后原车音频会瞬间抢焦点，短语音播报被掐 0.2 秒等于没播
                    if (loss == AudioManager.AUDIOFOCUS_LOSS) {
                        stop();
                    }
                }
            };
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                AudioFocusRequest.Builder b = new AudioFocusRequest.Builder(type)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(12).build())
                        .setOnAudioFocusChangeListener(l);
                AudioFocusRequest req = b.build();
                synchronized (STATE) { sFoc26 = req; }
                return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
            synchronized (STATE) { sFocOld = l; }
            return am.requestAudioFocus(l, 3, type) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Throwable t) {
            return true;  // 焦点拿不到不拦播放，照常出声
        }
    }

    private static void abandonFocus(AudioManager am, AudioFocusRequest f26,
                                     AudioManager.OnAudioFocusChangeListener fol) {
        try {
            if (f26 != null && am != null) am.abandonAudioFocusRequest(f26);
        } catch (Throwable ignored) { }
        try {
            if (fol != null && am != null) am.abandonAudioFocus(fol);
        } catch (Throwable ignored) { }
    }

    private static void restoreVolume(AudioManager am, int volType, int volOld) {
        try {
            if (volType >= 0 && volOld >= 0 && am != null) {
                am.setStreamVolume(volType, volOld, 0);
            }
        } catch (Throwable ignored) { }
    }

    private static final String TAG = "GreetPlayer";

    private GreetPlayer() { }
}
