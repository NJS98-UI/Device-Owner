package com.kooo.evcam.playback;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;

import java.io.IOException;

/**
 * TextureView 版 VideoView：与 android.widget.VideoView 的子集同名 API，
 * 专给回放页用。Desay ROM 上多路 SurfaceView（VideoView 底层）播放中会闪图层，
 * TextureView 走应用侧合成没有这个问题。
 * 语义差异：surface 还没就绪时 setVideoURI 也照常 prepare，surface 一到就 setSurface；
 * start/pause/seekTo 在 prepared 前调用会被记住（mWantPlay），prepared 后自动执行。
 */
public class TextureVideoView extends TextureView
        implements TextureView.SurfaceTextureListener {

    private MediaPlayer mPlayer;
    private Uri mPendingUri;
    private Surface mSurface;
    private boolean mWantPlay;
    private boolean mPrepared;
    private int mSeekOnPrepared = -1;

    private MediaPlayer.OnPreparedListener mOnPreparedListener;
    private MediaPlayer.OnCompletionListener mOnCompletionListener;
    private MediaPlayer.OnErrorListener mOnErrorListener;

    public TextureVideoView(Context context) { super(context); init(); }
    public TextureVideoView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public TextureVideoView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle); init();
    }

    private void init() {
        setSurfaceTextureListener(this);
        setOpaque(false);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        mSurface = new Surface(st);
        if (mPlayer != null) {
            try { mPlayer.setSurface(mSurface); } catch (Throwable ignored) { }
            if (mPrepared) {
                if (mWantPlay) {
                    safeStart();
                } else {
                    // 重挂后暂停态的 MediaPlayer 不会自己出帧（画面闪黑），seek 到
                    // 当前位置强制渲染一帧
                    try { mPlayer.seekTo(mPlayer.getCurrentPosition()); }
                    catch (Throwable ignored) { }
                }
            }
        } else if (mPendingUri != null) {
            // surface 在 setVideoURI 之后才就绪：此时才真正起播放器
            openPending();
        }
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (mPlayer != null) {
            try { mPlayer.setSurface(null); } catch (Throwable ignored) { }
        }
        if (mSurface != null) { mSurface.release(); mSurface = null; }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    public void setVideoURI(Uri uri) {
        stopPlayback();
        mPendingUri = uri;
        mPrepared = false;
        mWantPlay = false;
        mSeekOnPrepared = -1;
        if (mSurface == null) return;  // surface 到了再真正起播放器
        openPending();
    }

    private void openPending() {
        Uri uri = mPendingUri;
        if (uri == null || mSurface == null) return;
        mPendingUri = null;
        try {
            MediaPlayer mp = new MediaPlayer();
            mPlayer = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            mp.setSurface(mSurface);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) {
                    if (mPlayer != m) return;
                    mPrepared = true;
                    if (mSeekOnPrepared >= 0) {
                        try { m.seekTo(mSeekOnPrepared); } catch (Throwable ignored) { }
                        mSeekOnPrepared = -1;
                    }
                    if (mOnPreparedListener != null) mOnPreparedListener.onPrepared(m);
                    if (mWantPlay) safeStart();
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) {
                    if (mPlayer != m) return;
                    mWantPlay = false;
                    if (mOnCompletionListener != null) mOnCompletionListener.onCompletion(m);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    if (mPlayer != m) return true;
                    return mOnErrorListener != null && mOnErrorListener.onError(m, what, extra);
                }
            });
            mp.setDataSource(getContext(), uri);
            mp.prepareAsync();
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            if (mOnErrorListener != null) mOnErrorListener.onError(mPlayer, 1, 0);
            else releasePlayer();
        } catch (Throwable t) {
            releasePlayer();
        }
    }

    public void start() {
        mWantPlay = true;
        if (mPrepared && mPlayer != null) safeStart();
    }

    public void pause() {
        mWantPlay = false;
        if (mPrepared && mPlayer != null && mPlayer.isPlaying()) {
            try { mPlayer.pause(); } catch (Throwable ignored) { }
        }
    }

    public void seekTo(int msec) {
        if (mPrepared && mPlayer != null) {
            try { mPlayer.seekTo(msec); } catch (Throwable ignored) { }
        } else {
            mSeekOnPrepared = msec;
        }
    }

    public boolean isPlaying() {
        try { return mPlayer != null && mPlayer.isPlaying(); } catch (Throwable t) { return false; }
    }

    public int getCurrentPosition() {
        try { return mPlayer != null ? mPlayer.getCurrentPosition() : 0; }
        catch (Throwable t) { return 0; }
    }

    public int getDuration() {
        try { return mPlayer != null && mPrepared ? mPlayer.getDuration() : 0; }
        catch (Throwable t) { return 0; }
    }

    public void stopPlayback() {
        releasePlayer();
        mPendingUri = null;
        mPrepared = false;
        mWantPlay = false;
        mSeekOnPrepared = -1;
    }

    public void setOnPreparedListener(MediaPlayer.OnPreparedListener l) { mOnPreparedListener = l; }
    public void setOnCompletionListener(MediaPlayer.OnCompletionListener l) { mOnCompletionListener = l; }
    public void setOnErrorListener(MediaPlayer.OnErrorListener l) { mOnErrorListener = l; }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        releasePlayer();
    }

    private void safeStart() {
        try { mPlayer.start(); } catch (Throwable ignored) { }
    }

    private void releasePlayer() {
        MediaPlayer mp = mPlayer;
        mPlayer = null;
        if (mp == null) return;
        try { mp.setSurface(null); } catch (Throwable ignored) { }
        try { mp.stop(); } catch (Throwable ignored) { }
        try { mp.release(); } catch (Throwable ignored) { }
    }
}
