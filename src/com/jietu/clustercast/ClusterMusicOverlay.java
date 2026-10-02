package com.jietu.clustercast;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 仪表盘悬浮音乐（v18.42 改版，复刻原车数字模式音乐卡，2026-10-01 实车照片）：
 * 右侧小方形圆角封面（屏高 0.21，暂停时中央白色播放浮层），封面下第一行当前歌词
 * （LRC 按进度同步，深色大字），第二行歌手（灰色小字），无进度条。
 * 位置与原车一致：封面右缘 ~0.155 屏宽边距，顶部 ~0.34 屏高。
 */
public class ClusterMusicOverlay {
    private static final String TAG = "ClusterCast";
    private static final long REFRESH_MS = 1000;

    private static ClusterMusicOverlay sInst;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager.LayoutParams lp;
    private WindowManager wm;
    private ArtCardView card;
    private boolean attached;
    private String lastKey = "";
    private String lastArtKey = "";
    private Bitmap lastArt;
    private String lastLrcKey = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshNow();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private ClusterMusicOverlay(Context ctx) {
        app = ctx.getApplicationContext();
    }

    /** 打开仪表音乐悬浮（幂等）。 */
    public static synchronized void show(Context ctx) {
        if (sInst == null) sInst = new ClusterMusicOverlay(ctx);
        sInst.attach();
    }

    /** 关闭仪表音乐悬浮（幂等）。 */
    public static synchronized void hide() {
        if (sInst == null) return;
        sInst.detach();
    }

    public static boolean showing() {
        return sInst != null && sInst.attached;
    }

    /** 投屏悬浮层新挂上来后调用一次，保证音乐卡片重新排到最上层。 */
    public static void bringToFront() {
        if (sInst == null || !sInst.attached) return;
        sInst.main.post(new Runnable() { @Override public void run() { sInst.readd(); } });
    }

    private void attach() {
        if (attached) return;
        try {
            android.view.Display d = ClusterOverlay.findClusterDisplay(app);
            if (d == null) { Log.w(TAG, "仪表音乐：没找到仪表屏"); return; }
            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);
            Context dctx = app.createDisplayContext(d);

            // 原车数字模式实测（2026-10-01 照片标定，1920x720）：
            // 封面 ≈ 屏高 0.21，右缘边距 ≈ 0.155 屏宽，顶部 ≈ 0.34 屏高；
            // 封面下 歌词行（0.775 卡高处）+ 歌手行（0.94 卡高处）
            int art = (int) (m.heightPixels * 0.21f);
            int h = (int) (art * 1.74f);

            card = new ArtCardView(dctx, app);

            lp = new WindowManager.LayoutParams(art, h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.RIGHT;
            lp.x = (int) (m.widthPixels * 0.155f);
            lp.y = (int) (m.heightPixels * 0.34f);

            wm = (WindowManager) dctx.getSystemService(Context.WINDOW_SERVICE);
            wm.addView(card, lp);
            attached = true;
            lastKey = "";
            lastLrcKey = "";
            Log.i(TAG, "仪表音乐卡片已挂上 display " + d.getDisplayId() + " 尺寸 " + art + "x" + h);
            main.removeCallbacks(tick);
            main.post(tick);
        } catch (Throwable t) {
            Log.w(TAG, "仪表音乐悬浮挂载失败: " + t);
        }
    }

    private void detach() {
        attached = false;
        main.removeCallbacks(tick);
        if (card != null && wm != null) {
            try { wm.removeView(card); } catch (Throwable ignored) { }
        }
        card = null;
    }

    private void readd() {
        if (!attached || card == null) return;
        try {
            wm.removeView(card);
            wm.addView(card, lp);
        } catch (Throwable t) {
            Log.w(TAG, "仪表音乐卡片重排失败: " + t);
        }
    }

    private void refreshNow() {
        if (!attached) return;
        MediaController c = MusicListener.ready() ? MusicListener.inst().active() : null;
        MediaMetadata md = MusicListener.meta(c);
        String title = md != null ? md.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
        String artist = md != null ? md.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
        if (artist == null) artist = md != null
                ? md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) : null;
        if (title == null) title = "未在播放";
        if (artist == null) artist = "";
        boolean playing = MusicListener.playing(c);
        card.setText(title, artist, playing);

        // 播放位置：歌词按位置同步（原车卡没有进度条）
        long dur = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        long pos = dur > 0 ? MusicListener.position(c) : 0;
        card.setPosition(pos);

        // 封面：专辑图 bitmap，退而取封面 URI；换歌才重新解码
        String artKey = title + "|" + artist;
        String key = artKey + "|" + playing;
        if (!key.equals(lastKey)) {
            lastKey = key;
            if (md != null && artKey.equals(lastArtKey) && lastArt != null) {
                card.setArt(lastArt);
            } else {
                lastArt = md != null ? albumArt(md) : null;
                lastArtKey = artKey;
                card.setArt(lastArt);
            }
        }
        // 没封面就每秒补一发联网搜：ArtFetch 内部去重（在途/失败 60s 内不重发），
        // 请求完成后 CACHE 命中回调直接回来——只发一次的话回调可能被别的卡片挤掉
        if (lastArt == null && md != null && title.length() > 0 && !"未在播放".equals(title)) {
            lastArtKey = artKey;
            final String fk = artKey;
            ArtFetch.fetch(app, title, artist, new ArtFetch.Done() {
                @Override public void got(final Bitmap b) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (!attached || card == null) return;
                            lastArt = b;
                            lastArtKey = fk;
                            card.setArt(b);
                        }
                    });
                }
            });
        }

        // 歌词：元数据自带优先，没有就换歌时联网拉一次 LRC（按进度同步滚动）
        if (!artKey.equals(lastLrcKey)) {
            lastLrcKey = artKey;
            String metaLrc = metaLyric(md);
            if (metaLrc != null) {
                card.setLyric(artKey, metaLrc);
            } else {
                card.setLyric(artKey, null);
                if (title.length() > 0 && !"未在播放".equals(title)) {
                    final String fk = artKey;
                    ArtFetch.fetchLyric(title, artist, new ArtFetch.LyricDone() {
                        @Override public void got(final String lrc) {
                            main.post(new Runnable() {
                                @Override public void run() {
                                    if (!attached || card == null) return;
                                    card.setLyric(fk, lrc);
                                }
                            });
                        }
                    });
                }
            }
        }

        if (CastService.inst() != null && CastService.inst().castingPkg() != null) {
            // 投屏悬浮层可能盖在我们上面（后 add 的窗口更高），内容刷新时重排一次保 Z 序
            readd();
        }
    }

    /** 元数据里自带的歌词（酷狗/网易云会塞自定义 key），没有返回 null。 */
    private static String metaLyric(MediaMetadata md) {
        if (md == null) return null;
        String[] keys = {"android.media.metadata.LYRICS", "lyrics", "LYRICS"};
        for (String k : keys) {
            if (md.containsKey(k)) {
                String s = md.getString(k);
                if (s != null && s.trim().length() > 0) return s;
            }
        }
        return null;
    }

    /** 专辑封面 bitmap；没有则试封面 URI；再没有返回 null（画默认音符底）。 */
    private Bitmap albumArt(MediaMetadata md) {
        try {
            Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b != null && !b.isRecycled()) return b;
        } catch (Throwable ignored) { }
        try {
            String u = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (u == null) u = md.getString(MediaMetadata.METADATA_KEY_ART_URI);
            if (u != null) {
                android.net.Uri uri = android.net.Uri.parse(u);
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = 2;
                java.io.InputStream is = null;
                try {
                    is = app.getContentResolver().openInputStream(uri);
                    if (is != null) return android.graphics.BitmapFactory.decodeStream(is, null, o);
                } finally {
                    if (is != null) try { is.close(); } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * 自绘卡片（复刻原车数字模式）：小方形圆角封面（暂停时中央白色播放浮层）→
     * 当前行歌词（深色大字，LRC 同步）→ 歌手（灰色小字）。无进度条、无背景板。
     */
    private static class ArtCardView extends View {
        private static final Pattern LRC_TAG =
                Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

        private final Context app;
        private Bitmap art;
        private String title = "";
        private String artist = "";
        private boolean playing;
        private long posMs;

        private String lyricKey = "";
        private long[] lrcTimes;
        private String[] lrcTexts;
        private String lyricPlain = "";   // 无时间轴的兜底（元数据纯文本）
        private int lyricTick;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lyricPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint artistPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tmp = new RectF();

        ArtCardView(Context ctx, Context appCtx) {
            super(ctx);
            app = appCtx;
        }

        void setText(String t, String a, boolean p) {
            title = t == null ? "" : t;
            artist = a == null ? "" : a;
            playing = p;
            postInvalidate();
        }

        void setArt(Bitmap b) {
            art = b;
            postInvalidate();
        }

        void setPosition(long pos) {
            posMs = pos;
        }

        /** 换歌时灌歌词：lrc 带 [mm:ss.xx] 时间轴就解析成同步歌词，null/纯文本走兜底。 */
        void setLyric(String key, String lrc) {
            if (key == null ? lyricKey == null : key.equals(lyricKey)) return;
            lyricKey = key == null ? "" : key;
            lyricPlain = "";
            lrcTimes = null;
            lrcTexts = null;
            lyricTick = 0;
            if (lrc != null && lrc.trim().length() > 0) {
                ArrayList<Long> ts = new ArrayList<Long>();
                ArrayList<String> tx = new ArrayList<String>();
                parseLrc(lrc, ts, tx);
                if (!ts.isEmpty()) {
                    // 按时间排序（冒泡即可，行数少）
                    for (int i = 0; i < ts.size(); i++) {
                        for (int j = i + 1; j < ts.size(); j++) {
                            if (ts.get(j) < ts.get(i)) {
                                long tm = ts.get(i); ts.set(i, ts.get(j)); ts.set(j, tm);
                                String sm = tx.get(i); tx.set(i, tx.get(j)); tx.set(j, sm);
                            }
                        }
                    }
                    lrcTimes = new long[ts.size()];
                    lrcTexts = tx.toArray(new String[tx.size()]);
                    for (int i = 0; i < ts.size(); i++) lrcTimes[i] = ts.get(i);
                } else {
                    lyricPlain = firstText(lrc);
                }
            }
            postInvalidate();
        }

        private static void parseLrc(String raw, ArrayList<Long> ts, ArrayList<String> tx) {
            for (String rawLine : raw.split("\n")) {
                Matcher m = LRC_TAG.matcher(rawLine);
                ArrayList<Long> times = new ArrayList<Long>();
                int last = 0;
                while (m.find()) {
                    long t = Integer.parseInt(m.group(1)) * 60000L
                            + Integer.parseInt(m.group(2)) * 1000L;
                    if (m.group(3) != null) {
                        String frac = m.group(3);
                        long ms = Long.parseLong(frac);
                        t += frac.length() == 2 ? ms * 10 : (frac.length() == 1 ? ms * 100 : ms);
                    }
                    times.add(t);
                    last = m.end();
                }
                String text = rawLine.substring(last).replaceAll("<\\d{1,2}:\\d{1,2}>", "").trim();
                if (times.isEmpty() || text.length() == 0) continue;
                for (long t : times) { ts.add(t); tx.add(text); }
            }
        }

        private static String firstText(String raw) {
            for (String rawLine : raw.split("\n")) {
                String l = rawLine.replaceAll("\\[[^\\]]*\\]", "").trim();
                if (l.length() > 0) return l;
            }
            return "";
        }

        /** 当前应显示的歌词行下标（最后一个 time<=pos 的行）。 */
        private int lyricIndex() {
            if (lrcTimes == null) return -1;
            int idx = -1;
            for (int i = 0; i < lrcTimes.length; i++) {
                if (lrcTimes[i] <= posMs) idx = i; else break;
            }
            return idx;
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float radius = Ui.dp(app, 10);

            // ---- 封面：方形圆角，center-crop；暂停时中央白色播放浮层 ----
            cv.save();
            tmp.set(0, 0, w, w);
            Path clip = new Path();
            clip.addRoundRect(tmp, radius, radius, Path.Direction.CW);
            cv.clipPath(clip);
            if (art != null && !art.isRecycled()) {
                paint.setShader(null);
                paint.setAlpha(255);
                float scale = Math.max(w / art.getWidth(), w / art.getHeight());
                float bw = art.getWidth() * scale, bh = art.getHeight() * scale;
                cv.drawBitmap(art, (w - bw) / 2f, (w - bh) / 2f, paint);
            } else {
                paint.setShader(new RadialGradient(w / 2f, w / 2f, w * 0.7f,
                        0xFF232B38, 0xFF11151D, Shader.TileMode.CLAMP));
                cv.drawRect(0, 0, w, w, paint);
                paint.setShader(null);
                paint.setColor(playing ? 0xFF6FA8FF : 0xFF5A6577);
                paint.setTextSize(w * 0.42f);
                paint.setTextAlign(Paint.Align.CENTER);
                cv.drawText("\u266A", w / 2f, w * 0.62f, paint);
            }
            cv.restore();
            if (!playing) {
                // 白色播放浮层：半透明白圆 + 白三角（原车暂停样式）
                float r = w * 0.17f;
                paint.setShader(null);
                paint.setColor(0x99FFFFFF);
                cv.drawCircle(w / 2f, w / 2f, r, paint);
                paint.setColor(0xFFFFFFFF);
                float t = r * 0.62f;
                Path tri = new Path();
                tri.moveTo(w / 2f - t * 0.55f, w / 2f - t);
                tri.lineTo(w / 2f - t * 0.55f, w / 2f + t);
                tri.lineTo(w / 2f + t * 0.95f, w / 2f);
                tri.close();
                cv.drawPath(tri, paint);
            }

            // ---- 歌词行：当前 LRC 行，深色大字（原车浅底配色）----
            float lyricY = h * 0.775f;
            lyricPaint.setTextAlign(Paint.Align.CENTER);
            String cur = null;
            if (lrcTimes != null) {
                int idx = lyricIndex();
                if (idx >= 0) cur = lrcTexts[idx];
                if (cur == null && lrcTexts.length > 0) cur = "…";
            } else if (lyricPlain.length() > 0) {
                cur = lyricPlain;
            } else {
                cur = "未在播放".equals(title) ? title : title;
            }
            lyricPaint.setColor(0xFF2A2F36);
            lyricPaint.setTextSize(w * 0.21f);
            lyricPaint.setFakeBoldText(true);
            cv.drawText(ellipsize(cur, 14), w / 2f, lyricY, lyricPaint);

            // ---- 歌手行：灰色小字 ----
            if (artist.length() > 0) {
                artistPaint.setTextAlign(Paint.Align.CENTER);
                artistPaint.setColor(0xFF8A929C);
                artistPaint.setTextSize(w * 0.15f);
                artistPaint.setFakeBoldText(false);
                cv.drawText(ellipsize(artist, 16), w / 2f, h - Ui.dp(app, 4), artistPaint);
            }
        }

        private static String ellipsize(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max - 1) + "…";
        }
    }
}
