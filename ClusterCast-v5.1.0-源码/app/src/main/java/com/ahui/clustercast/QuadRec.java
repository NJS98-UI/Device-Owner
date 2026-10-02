package com.ahui.clustercast;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * 四宫格单文件录像器，按盯盯车（com.sunslin.surcamx 1.2.8 反汇编实证）的录像器
 * 行为改造：视频（createInputSurface→AVC）+ 音轨（AudioRecord→AAC）复用进同一个
 * mp4。只搬它自写的环节；它的联网/云端/车辆信号通道一概不碰。
 *
 * 对齐它三件事：
 *   · 无缝换段：编码器/拼接层完全不动，在 IDR 帧边界收旧 muxer、开新 muxer
 *     （它的日志串 "Seamless switch to segment"）。等不到边界报超时，
 *     由 CamCtl 退回整层重建（它的 "Encoder recreate segment switch" 兜底）。
 *   · 看门狗两探：framesOut()/silentMs() 对应 "WATCHDOG TRIGGERED: No write for"
 *     和 "File size is 0! No frames received!"。
 *   · 段内时间戳从 0 起算（pts-basePts），每个新文件开头补参数集。
 *
 * 音轨是尽力而为：没给 RECORD_AUDIO、麦克风被原车占用，audioNote 里放真实原因，
 * 视频腿照常录 —— 绝不因为音频起不来就说录像失败，也绝不假装"带声音"。
 *
 * 用法（调用在 CamCtl 的 bg 线程）：
 *   QuadRec q = new QuadRec(...); q.start(withAudio)  // null=成功，否则一句原因
 *   q.getInputSurface()                               // 给拼接层
 *   q.switchSegment(f2, 6000)                         // 无缝续录
 *   q.stop()
 */
public class QuadRec {

    private static final String MIME = "video/avc";
    private static final String TAGX = "ClusterCast.QuadRec";
    private static final int SR = 16000;

    private final File file;
    private final int w, h, bitrate, fps;

    private final Object lock = new Object();   // 所有 muxer 触动的唯一入口（两泵共享）

    // ---------- 视频腿 ----------
    private MediaCodec codec = null;
    private Surface input = null;
    private Thread vThread = null;
    private volatile boolean eos = false;
    private volatile int vTrack = -1;
    private MediaFormat vFmt = null;
    private byte[] vCsd = null;
    /** 段首帧的真实 pts：视频轨全部减它归零。不减的话 Surface 编码器给的是
     *  开机以来的微秒（巨大值），而音轨从 0 数 —— 一条 mp4 里两条轨差出十几分钟，
     *  播放器认了音轨的长度：正是「录两秒就断、文件是坏的」的根因。 */
    private long vBase = -1L;

    // ---------- muxer / 换段 ----------
    private MediaMuxer muxer = null;
    private volatile boolean muxReady = false;
    private volatile File pendFile = null;
    /** 0=没有换段在等；1=已换完；2=换段失败（原因在 swErr）。 */
    private volatile int swState = 0;
    private volatile String swErr = null;

    // ---------- 看门狗计数 ----------
    private volatile long lastOutAt = SystemClock.elapsedRealtime();
    private volatile long frames = 0L;

    // ---------- 音频腿 ----------
    private MediaCodec aCodec = null;
    private AudioRecord aRecord = null;
    private Thread aThread = null;
    private volatile int aTrack = -1;
    private volatile boolean aEos = false;
    private MediaFormat aFmt = null;       // configure 时就定好，addTrack 用
    private byte[] aCsd = null;
    private long aBase = -1L;
    private boolean audioOn = false;
    /** 音频起不来的真实原因；null=正常或没开。视频不受它影响。 */
    private volatile String audioNote = null;

    public String getAudioNote() { synchronized (lock) { return audioNote; } }

    // muxer 还没 start 时攒音频真帧（~4 秒封顶，丢最旧），start 后一次冲掉。
    private final ArrayList<byte[]> aPendB = new ArrayList<>();
    private final ArrayList<Long> aPendT = new ArrayList<>();

    public Surface getInputSurface() { return input; }

    public QuadRec(File file, int w, int h, int bitrate, int fps) {
        this.file = file; this.w = w; this.h = h; this.bitrate = bitrate; this.fps = fps;
    }

    /** 视频泵最后真正写进文件的帧数。 */
    public long framesOut() { return frames; }
    /** 距上一次写视频样本多久（毫秒）——看门狗主探针。 */
    public long silentMs() { return SystemClock.elapsedRealtime() - lastOutAt; }

    public String start(final boolean withAudio) {
        String verr;
        try {
            MediaFormat mf = MediaFormat.createVideoFormat(MIME, w, h);
            mf.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            mf.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            mf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            mf.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            mf.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            MediaCodec c = MediaCodec.createEncoderByType(MIME);
            c.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = c.createInputSurface();
            c.start();
            codec = c;
            muxer = new MediaMuxer(file.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Thread t = new Thread(new Runnable() {
                @Override public void run() { pumpVideo(c); }
            });
            vThread = t;
            t.start();
            verr = null;
        } catch (Throwable t) {
            release();
            verr = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        if (verr != null) return verr;
        if (withAudio) {
            String aerr = tryAudio();
            if (aerr != null) audioNote = aerr; else audioOn = true;
        }
        return null;
    }

    /** AudioRecord + AAC 编码器；任何一步不成都带回真实原因，不碰视频腿。 */
    private String tryAudio() {
        try {
            int min = minAudioBuf();
            final AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                try { rec.release(); } catch (Throwable t) { }
                return "麦克风初始化失败（多半被原车语音/倒车音源占着）";
            }
            try {
                final MediaCodec c = MediaCodec.createEncoderByType("audio/mp4a-latm");
                MediaFormat f = MediaFormat.createAudioFormat("audio/mp4a-latm", SR, 1);
                f.setInteger(MediaFormat.KEY_BIT_RATE, 96_000);
                f.setInteger(MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                rec.startRecording();
                c.start();
                aRecord = rec; aCodec = c; aFmt = f;
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { pumpAudio(c, rec); }
                });
                aThread = t;
                t.start();
                return null;
            } catch (Throwable t) {
                try { rec.release(); } catch (Throwable u) { }
                return "音频编码器起不来：" + t.getClass().getSimpleName();
            }
        } catch (SecurityException t) {
            return "没给录音权限（RECORD_AUDIO 被拒）";
        } catch (Throwable t) {
            return "音频起不来：" + t.getClass().getSimpleName();
        }
    }

    /** HAL 偶尔给 0 或负数，兜底 2048。 */
    private static int minAudioBuf() {
        try {
            int m = AudioRecord.getMinBufferSize(16_000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            return m > 0 ? m : 2048;
        } catch (Throwable t) { return 2048; }
    }

    /**
     * 无缝切段：发强制 IDR，等视频泵在边界换 muxer。
     * @return null=换好；否则真实原因（调用方退回整层重建）。
     */
    public String switchSegment(File f, long timeoutMs) {
        MediaCodec c = codec;
        if (c == null) return "没在录";
        synchronized (lock) { pendFile = f; swState = 0; swErr = null; }
        try {
            Bundle bp = new Bundle();
            bp.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            c.setParameters(bp);
        } catch (Throwable t) {
            synchronized (lock) { pendFile = null; }
            return "发不了强制关键帧：" + t.getClass().getSimpleName();
        }
        long t0 = SystemClock.elapsedRealtime();
        while (swState == 0 && SystemClock.elapsedRealtime() - t0 < timeoutMs)
            try { Thread.sleep(40); } catch (Throwable t) { break; }
        if (swState == 1) return null;
        if (swState == 2) return swErr;
        synchronized (lock) { pendFile = null; }
        return "等关键帧边界超时（" + timeoutMs + "ms）";
    }

    /** 收尾：两路泵各自吐完 EOS 再拆。@return null=成功。 */
    public String stop() {
        MediaCodec c = codec;
        if (c == null) return "没在录";
        String err = null;
        try {
            c.signalEndOfInputStream();
            eos = true;
            aEos = true;
            try { if (vThread != null) vThread.join(4000); } catch (Throwable t) { }
            try { if (aThread != null) aThread.join(2000); } catch (Throwable t) { }
        } catch (Throwable t) {
            err = t.getClass().getSimpleName();
        }
        release();
        return err;
    }

    // ---------- 视频泵 ----------

    private void pumpVideo(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int idx = c.dequeueOutputBuffer(info, 10_000);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER && !eos) continue;
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized (lock) { vFmt = c.getOutputFormat(); }
                    maybeStartMux();
                    continue;
                }
                if (idx < 0) continue;
                ByteBuffer buf = c.getOutputBuffer(idx);
                if (buf == null || info.size <= 0) {
                    c.releaseOutputBuffer(idx, false); continue;
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    buf.position(info.offset);
                    byte[] b = new byte[info.size];
                    buf.get(b, 0, info.size);
                    synchronized (lock) { vCsd = b; }
                    c.releaseOutputBuffer(idx, false);
                    continue;           // csd 已存下；addTrack 的 format 自带 csd，不必转写
                }
                int p = buf.position();
                boolean key = isKeyframe(buf, info);
                buf.position(p);
                if (key) {
                    File f;
                    synchronized (lock) { f = pendFile; }
                    if (f != null) doSwitch(f, info.presentationTimeUs);
                }
                // 首帧锁定基准：Surface 编码器的 pts 是系统时间轴（开机以来的微秒），
                // 不归零的话第一段文件里视频轨在"几小时后"、音轨在 0 —— 播放器
                // 直接判定文件坏/两秒就没画面。vBase 只在本线程读写（doSwitch 同线程）。
                if (vBase < 0) vBase = info.presentationTimeUs;
                long pts = info.presentationTimeUs - vBase;
                MediaCodec.BufferInfo ci = new MediaCodec.BufferInfo();
                ci.set(info.offset, info.size, pts, info.flags);
                buf.position(info.offset); buf.limit(info.offset + info.size);
                boolean ok;
                synchronized (lock) {
                    if (!muxReady || vTrack < 0) ok = false;
                    else { try { muxer.writeSampleData(vTrack, buf, ci); ok = true; }
                           catch (Throwable t) { ok = false; } }
                }
                if (ok) {
                    frames++;
                    lastOutAt = SystemClock.elapsedRealtime();
                }
                c.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        } catch (Throwable t) {
            Log.i(TAGX, "视频泵退出：" + t.getClass().getSimpleName());
            synchronized (lock) {
                if (pendFile != null) {
                    swErr = "视频泵异常：" + t.getClass().getSimpleName();
                    pendFile = null; swState = 2;
                }
            }
        }
    }

    /** AnnexB 起始码后第一个 NAL：5=IDR、7=SPS 认作新段第一帧。 */
    private boolean isKeyframe(ByteBuffer buf, MediaCodec.BufferInfo info) {
        int i = info.offset;
        int end = info.offset + info.size - 4;
        while (i < end) {
            if (buf.get(i) == 0 && buf.get(i + 1) == 0 &&
                    buf.get(i + 2) == 0 && buf.get(i + 3) == 1) {
                int t = buf.get(i + 4) & 0x1F;
                return t == 5 || t == 7;
            }
            i++;
        }
        return false;
    }

    /** IDR 边界换 muxer（视频泵调用，全程持锁——音频泵写也走这把锁）。 */
    private void doSwitch(File f, long atPts) {
        MediaFormat vf;
        synchronized (lock) { vf = vFmt; }
        if (vf == null) {
            synchronized (lock) { pendFile = null; swErr = "还没拿到视频编码格式"; swState = 2; }
            return;
        }
        synchronized (lock) {
            if (pendFile == null) return;
            try {
                muxReady = false;
                try { if (muxer != null) muxer.stop(); } catch (Throwable t) { }
                try { if (muxer != null) muxer.release(); } catch (Throwable t) { }
                MediaMuxer m = new MediaMuxer(f.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                vTrack = m.addTrack(vf);
                if (audioOn) aTrack = m.addTrack(aFmt);
                m.start();
                muxer = m;
                vBase = atPts;
                aBase = -1L;
                frames = 0L;
                writeCsd(m, vTrack, vCsd);
                if (audioOn) { writeCsd(m, aTrack, aCsd); drainAPend(m); }
                muxReady = true;
                pendFile = null; swState = 1;
                Log.i(TAGX, "Seamless switch -> " + f.getName());
            } catch (Throwable t) {
                pendFile = null; swState = 2;
                swErr = "换 muxer 失败：" + t.getClass().getSimpleName();
            }
        }
    }

    // ---------- muxer 起建（两泵都可能触发，锁内定夺） ----------

    private void maybeStartMux() {
        synchronized (lock) {
            if (muxReady) return;
            if (muxer == null) return;
            MediaMuxer m = muxer;
            if (vFmt == null) return;
            if (audioOn && aFmt == null) return;       // 等两路 format 齐
            try {
                vTrack = m.addTrack(vFmt);
                if (audioOn) aTrack = m.addTrack(aFmt);
                m.start();
                muxReady = true;
                writeCsd(m, vTrack, vCsd);
                if (audioOn) { writeCsd(m, aTrack, aCsd); drainAPend(m); }
            } catch (Throwable t) {
                Log.w(TAGX, "muxer 起建失败：" + t);
            }
        }
    }

    private void writeCsd(MediaMuxer m, int track, byte[] csd) {
        if (csd == null || track < 0) return;
        try {
            ByteBuffer b = ByteBuffer.wrap(csd);
            MediaCodec.BufferInfo ci = new MediaCodec.BufferInfo();
            ci.set(0, csd.length, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
            m.writeSampleData(track, b, ci);
        } catch (Throwable t) { }
    }

    // ---------- 音频泵 ----------

    private void pumpAudio(MediaCodec c, AudioRecord rec) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] buf = new byte[SR * 2 / 25];            // 40ms @16k mono 16bit
        long feedPts = 0L;
        boolean inDone = false;
        try {
            while (true) {
                if (!inDone) {
                    if (eos || aEos) {
                        int i2 = c.dequeueInputBuffer(5_000);
                        if (i2 >= 0) {
                            c.queueInputBuffer(i2, 0, 0, feedPts,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        }
                    } else {
                        int n;
                        try { n = rec.read(buf, 0, buf.length); } catch (Throwable t) { n = -1; }
                        if (n > 0) {
                            int i2 = c.dequeueInputBuffer(10_000);
                            if (i2 >= 0) {
                                ByteBuffer ib = c.getInputBuffer(i2);
                                ib.clear(); ib.put(buf, 0, n);
                                c.queueInputBuffer(i2, 0, n, feedPts, 0);
                                feedPts += n * 1_000_000L / (SR * 2);
                            }
                        } else if (n < 0) inDone = true;   // 设备死了：下面直接吐 EOS
                    }
                }
                int idx = c.dequeueOutputBuffer(info, 10_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { maybeStartMux(); continue; }
                if (idx < 0) {
                    if (inDone && idx == MediaCodec.INFO_TRY_AGAIN_LATER &&
                            (eos || aEos)) break;        // EOS 永远等不到也不吊死收尾
                    continue;
                }
                ByteBuffer ob = c.getOutputBuffer(idx);
                if (ob != null && info.size > 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        ob.position(info.offset);
                        byte[] b = new byte[info.size]; ob.get(b, 0, info.size);
                        synchronized (lock) { aCsd = b; }
                    } else if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                        long pts;
                        synchronized (lock) {
                            if (aBase < 0) { aBase = info.presentationTimeUs; pts = 0L; }
                            else pts = info.presentationTimeUs - aBase;
                        }
                        ob.position(info.offset); ob.limit(info.offset + info.size);
                        byte[] arr = new byte[info.size]; ob.get(arr, 0, info.size);
                        boolean wrote;
                        synchronized (lock) {
                            if (!muxReady || aTrack < 0) {
                                if (aPendB.size() > 100) { aPendB.remove(0); aPendT.remove(0); }
                                aPendB.add(arr); aPendT.add(pts); wrote = false;
                            } else try {
                                MediaCodec.BufferInfo ci = new MediaCodec.BufferInfo();
                                ci.set(0, arr.length, pts, info.flags);
                                muxer.writeSampleData(aTrack, ByteBuffer.wrap(arr), ci);
                                wrote = true;
                            } catch (Throwable t) { wrote = false; }
                        }
                    }
                }
                c.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        } catch (Throwable t) {
            Log.i(TAGX, "音频泵退出：" + t.getClass().getSimpleName());
        }
    }

    /** 冲掉 muxer 起建前攒的音频帧（调用方持锁）。 */
    private void drainAPend(MediaMuxer m) {
        if (aTrack < 0) return;
        for (int i = 0; i < aPendB.size(); i++) {
            MediaCodec.BufferInfo ci = new MediaCodec.BufferInfo();
            ci.set(0, aPendB.get(i).length, aPendT.get(i), 0);
            try { m.writeSampleData(aTrack, ByteBuffer.wrap(aPendB.get(i)), ci); }
            catch (Throwable t) { break; }
        }
        aPendB.clear(); aPendT.clear();
    }

    private void release() {
        try { if (codec != null) codec.stop(); } catch (Throwable t) { }
        try { if (codec != null) codec.release(); } catch (Throwable t) { }
        try { if (input != null) input.release(); } catch (Throwable t) { }
        try { if (aCodec != null) aCodec.stop(); } catch (Throwable t) { }
        try { if (aCodec != null) aCodec.release(); } catch (Throwable t) { }
        try { if (aRecord != null) aRecord.stop(); } catch (Throwable t) { }
        try { if (aRecord != null) aRecord.release(); } catch (Throwable t) { }
        synchronized (lock) {
            try { if (muxer != null) muxer.stop(); } catch (Throwable t) { }
            try { if (muxer != null) muxer.release(); } catch (Throwable t) { }
            muxer = null;
        }
        aRecord = null;
        codec = null; input = null; aCodec = null;
        vTrack = -1; aTrack = -1;
    }
}
