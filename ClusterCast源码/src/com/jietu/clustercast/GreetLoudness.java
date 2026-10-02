package com.jietu.clustercast;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

/**
 * 播报音频自动响度归一：不同来源的 MP3 录音响度差很多（酷狗语音包各条不一），
 * 通道 4 只管混音路由不管响度，所以听起来忽大忽小。
 * 播放前用 MediaCodec 解码一次算 RMS（结果缓存），换算成 LoudnessEnhancer 增益，
 * 轻的升、响的降，全部对齐到同一响度。任何异常都回落 0（不改音量）。
 */
final class GreetLoudness {

    /** 目标响度：满幅 RMS 0.08 ≈ -22 dBFS，语音常用的舒适响度。 */
    private static final float TARGET_RMS = 0.08f;
    /** 增益上限（mB）：降最多 -12dB，升最多 +6dB（正增益过大可能在输出端削波）。 */
    private static final int MIN_MB = -1200, MAX_MB = 600;
    /** 单条最多解码 30 秒，够算响度了。 */
    private static final long MAX_SAMPLES = 44100L * 30;

    private static final HashMap<String, Integer> CACHE = new HashMap<String, Integer>();

    /** 返回自动增益（mB）。必须在工作线程调用；主线程直接返回 0。 */
    static int autoGainMb(Context ctx, int rawRes, String filePath) {
        if (ctx == null || Looper.myLooper() == Looper.getMainLooper()) return 0;
        try {
            final String key;
            if (rawRes > 0) {
                key = "raw:" + ctx.getResources().getResourceEntryName(rawRes);
            } else if (filePath != null) {
                File f = new File(filePath);
                if (!f.exists()) return 0;
                key = filePath + ":" + f.length() + ":" + f.lastModified();
            } else {
                return 0;
            }
            synchronized (CACHE) {
                Integer hit = CACHE.get(key);
                if (hit != null) return hit.intValue();
            }
            float rms = rawRes > 0 ? measureRes(ctx, rawRes) : measureFile(filePath);
            int mb = 0;
            if (rms > 0f) {
                int db10 = (int) Math.round(200.0 * Math.log10(TARGET_RMS / rms)); // 0.1dB
                mb = clamp(db10 * 10);
            }
            synchronized (CACHE) { CACHE.put(key, Integer.valueOf(mb)); }
            return mb;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int clamp(int mb) {
        return mb < MIN_MB ? MIN_MB : (mb > MAX_MB ? MAX_MB : mb);
    }

    /** 内置 raw（mp3 等）经 MediaExtractor+MediaCodec 解码算 RMS。 */
    private static float measureRes(Context ctx, int rawRes) throws Exception {
        android.content.res.AssetFileDescriptor afd = null;
        MediaExtractor ex = new MediaExtractor();
        try {
            afd = ctx.getResources().openRawResourceFd(rawRes);
            ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            return decodeRms(ex);
        } finally {
            try { ex.release(); } catch (Throwable ignored) { }
            if (afd != null) try { afd.close(); } catch (Throwable ignored) { }
        }
    }

    private static float measureFile(String path) throws Exception {
        if (path.toLowerCase().endsWith(".pcm")) {
            // 裸 PCM 44100/单声道/16bit：直接读字节算，MediaExtractor 不认无头 PCM
            InputStream in = null;
            try {
                in = new FileInputStream(path);
                byte[] b = new byte[16384];
                long sum = 0; long n = 0;
                int r;
                while (n < MAX_SAMPLES && (r = in.read(b)) > 0) {
                    for (int i = 0; i + 1 < r; i += 2) {
                        short s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
                        sum += (long) s * s;
                        n++;
                    }
                }
                return n > 0 ? (float) Math.sqrt((double) sum / n) / 32768f : 0f;
            } finally {
                try { if (in != null) in.close(); } catch (Throwable ignored) { }
            }
        }
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(path);
            return decodeRms(ex);
        } finally {
            try { ex.release(); } catch (Throwable ignored) { }
        }
    }

    private static float decodeRms(MediaExtractor ex) throws Exception {
        int ti = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { ti = i; break; }
        }
        if (ti < 0) return 0f;
        ex.selectTrack(ti);
        MediaFormat fmt = ex.getTrackFormat(ti);
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long sumSq = 0, n = 0;
            boolean eosIn = false, eosOut = false;
            while (!eosOut && n < MAX_SAMPLES) {
                int inIdx = eosIn ? -1 : codec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer bb = codec.getInputBuffer(inIdx);
                    int sz = bb == null ? 0 : ex.readSampleData(bb, 0);
                    if (sz < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eosIn = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sz, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                while (outIdx >= 0 && n < MAX_SAMPLES) {
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if (ob != null) {
                        ob.order(ByteOrder.LITTLE_ENDIAN).position(info.offset);
                        ob.limit(info.offset + info.size);
                        int cnt = ob.remaining() / 2;
                        for (int i = 0; i < cnt && n < MAX_SAMPLES; i++) {
                            short s = ob.getShort();
                            sumSq += (long) s * s;
                            n++;
                        }
                    }
                    boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIdx, false);
                    if (end) { eosOut = true; break; }
                    outIdx = codec.dequeueOutputBuffer(info, 0);
                }
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && eosIn && !eosOut) {
                    // 解码器已收 EOS，继续等输出
                }
            }
            return n > 0 ? (float) Math.sqrt((double) sumSq / n) / 32768f : 0f;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) { }
                try { codec.release(); } catch (Throwable ignored) { }
            }
        }
    }

    private GreetLoudness() { }
}
