package com.jietu.clustercast;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 桌面音乐卡片自绘：原车桌面左下角卡片的数据源是 com.desaysv.mediacenter 的聚合
 * 会话，它只认自带音源（网易云 NTES/云听 YT/蓝牙/USB），酷狗等第三方会话永远
 * 不进卡片；总线代发 VDMediaItem（393218）实车实测 set 后回读 null，写不进去。
 * 于是在原卡片同一位置（1920x1080 实测 x=20 y=772 w=532 h=180）自绘同款卡片：
 * 歌名+歌手+上一首/播放/下一首+右侧圆角封面。活动会话是媒体中心自己（或没在
 * 放）时自动隐藏，把卡片还给原车。
 */
public class DesktopMusicCard {
    private static final String TAG = "ClusterCast";
    private static final long REFRESH_MS = 1000;
    private static final String MC_PKG = "com.desaysv.mediacenter";

    private static DesktopMusicCard sInst;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private CardView card;
    private boolean attached;

    private MediaController cur;
    private String title = "", artist = "";
    private boolean playing;
    private Bitmap art;
    private String artKey = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshNow();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    private DesktopMusicCard(Context ctx) {
        app = ctx.getApplicationContext();
    }

    /** 启动轮询（幂等）：由 CastService 按音乐卡片开关拉起。 */
    public static synchronized void start(Context ctx) {
        if (sInst == null) sInst = new DesktopMusicCard(ctx);
        sInst.main.removeCallbacks(sInst.tick);
        sInst.main.post(sInst.tick);
    }

    /** 关掉：开关切走/服务退出时调用。 */
    public static synchronized void stop() {
        if (sInst == null) return;
        sInst.main.removeCallbacks(sInst.tick);
        sInst.detach();
    }

    private void detach() {
        if (card != null && wm != null) {
            try { wm.removeView(card); } catch (Throwable ignored) { }
        }
        card = null;
        wm = null;
        attached = false;
    }

    /**
     * 当前前台应用包名；读不到返回 null。设备主能走 getRunningTasks；
     * 被限制时它只回自己的任务，这种情况退回用法统计（需要 PACKAGE_USAGE_STATS）。
     */
    private String topApp() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    app.getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.RunningTaskInfo> ts =
                    am.getRunningTasks(1);
            if (ts != null && !ts.isEmpty() && ts.get(0).topActivity != null) {
                String top = ts.get(0).topActivity.getPackageName();
                if (top != null && !top.equals(app.getPackageName())) return top;
            }
        } catch (Throwable ignored) { }
        try {
            android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager)
                    app.getSystemService("usagestats");
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents ev = usm.queryEvents(now - 30000, now);
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            String top = null;
            long topT = -1;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
                        && e.getTimeStamp() >= topT) {
                    topT = e.getTimeStamp();
                    top = e.getPackageName();
                }
            }
            return top;
        } catch (Throwable t) {
            return null;
        }
    }

    private void refreshNow() {
        MediaController c = null;
        try {
            if (MusicListener.ready()) c = MusicListener.inst().active();
        } catch (Throwable ignored) { }
        String pkg = c != null ? c.getPackageName() : null;
        boolean third = c != null && pkg != null && !pkg.equals(MC_PKG);
        MediaMetadata md = third ? c.getMetadata() : null;
        if (md != null && md.getString(MediaMetadata.METADATA_KEY_TITLE) == null) md = null;
        // 只在原车桌面上显示：别的界面盖住原车卡片是打扰（开关切走时也不显示）
        boolean onDesk = false;
        if (md != null) {
            String top = topApp();
            onDesk = "com.desaysv.launcher".equals(top);
        }
        if (!third || md == null || !onDesk) {
            // 原车音源在播/不在桌面（或没数据）：隐掉自己，卡片还给原车
            cur = null;
            if (attached && card != null) card.setVisibility(View.GONE);
            return;
        }
        cur = c;
        String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (a == null) a = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (a == null) a = "";
        title = t;
        artist = a;
        playing = MusicListener.playing(c);

        if (!attached) attach();
        if (card == null) return;
        card.setVisibility(View.VISIBLE);
        card.setText(title, artist, playing);

        // 封面：换歌才重新解码会话图；没图就每秒补一发联网搜（ArtFetch 内部
        // 去重，仪表卡片在途/已失败 60s 内都不会重发；请求完成后 CACHE 命中
        // 回调直接回来——不然仪表先发的请求会把我们的回调挤掉，占位图换不掉）
        String key = title + "|" + artist;
        if (!key.equals(artKey)) {
            artKey = key;
            art = albumArt(md);
            card.setArt(art);
        }
        if (art == null) {
            final String fk = artKey;
            ArtFetch.fetch(app, title, artist, new ArtFetch.Done() {
                @Override public void got(final Bitmap b) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (sInst == null || card == null || !fk.equals(artKey)) return;
                            art = b;
                            card.setArt(b);
                        }
                    });
                }
            });
        }
    }

    private void attach() {
        if (attached) return;
        try {
            // 1920x1080 主屏实测（2026-10-01 桌面截图标定）：卡片 532x180 @ (20,772)
            float s = app.getResources().getDisplayMetrics().widthPixels / 1920f;
            int w = Math.round(532 * s);
            int h = Math.round(180 * s);
            card = new CardView(app, s);
            card.setText(title, artist, playing);
            card.setArt(art);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = Math.round(20 * s);
            lp.y = Math.round(772 * s);
            wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            wm.addView(card, lp);
            attached = true;
            Log.i(TAG, "桌面音乐卡片已挂上 " + w + "x" + h);
        } catch (Throwable t) {
            Log.w(TAG, "桌面音乐卡片挂载失败: " + t);
        }
    }

    /** 会话自带的封面：专辑图 bitmap → 封面 URI 解码；都没有返回 null。 */
    private Bitmap albumArt(MediaMetadata md) {
        try {
            Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b != null && !b.isRecycled()) return b;
        } catch (Throwable ignored) { }
        try {
            String u = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (u == null) u = md.getString(MediaMetadata.METADATA_KEY_ART_URI);
            if (u == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            java.io.InputStream is = app.getContentResolver().openInputStream(Uri.parse(u));
            if (is == null) return null;
            try {
                return BitmapFactory.decodeStream(is, null, o);
            } finally {
                try { is.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 卡片视图：532x180 设计坐标按 scale 画，触摸按区域分发给会话控制。 */
    private final class CardView extends View {
        private final float s;
        private String title = "", artist = "";
        private boolean playing;
        private Bitmap art;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        CardView(Context c, float scale) {
            super(c);
            s = scale;
        }

        void setText(String t, String a, boolean play) {
            if (!t.equals(title) || !a.equals(artist) || play != playing) {
                title = t;
                artist = a;
                playing = play;
                invalidate();
            }
        }

        void setArt(Bitmap b) {
            if (b != art) {
                art = b;
                invalidate();
            }
        }

        @Override protected void onDraw(Canvas cv) {
            cv.save();
            cv.scale(s, s);
            // 卡片底：白圆角（同原车浅色卡）
            p.setColor(0xF5FFFFFF);
            cv.drawRoundRect(new RectF(0, 0, 532, 180), 26, 26, p);

            // 封面：右侧 128x128 圆角
            float ax = 368, ay = 26, as = 128;
            if (art != null && !art.isRecycled()) {
                p.setColor(0xFFE8EAED);
                cv.drawRoundRect(new RectF(ax, ay, ax + as, ay + as), 18, 18, p);
                p.setColor(0xFF333333);
                Path clip = new Path();
                clip.addRoundRect(new RectF(ax, ay, ax + as, ay + as), 18, 18, Path.Direction.CW);
                cv.save();
                cv.clipPath(clip);
                float bs = Math.max(as / (float) art.getWidth(), as / (float) art.getHeight());
                float bw = art.getWidth() * bs, bh = art.getHeight() * bs;
                cv.drawBitmap(art, ax + (as - bw) / 2f, ay + (as - bh) / 2f, null);
                cv.restore();
            } else {
                // 没封面：浅灰底 + 音符占位
                p.setColor(0xFFE8EAED);
                cv.drawRoundRect(new RectF(ax, ay, ax + as, ay + as), 18, 18, p);
                p.setColor(0xFFB4BAC2);
                p.setTextSize(64);
                p.setTextAlign(Paint.Align.CENTER);
                cv.drawText("♪", ax + as / 2f, ay + as / 2f + 22, p);
                p.setTextAlign(Paint.Align.LEFT);
            }

            // 歌名 / 歌手
            p.setColor(0xFF20242A);
            p.setFakeBoldText(true);
            p.setTextSize(34);
            cv.drawText(ellipsize(title, 320), 40, 56, p);
            p.setFakeBoldText(false);
            p.setColor(0xFF8A929C);
            p.setTextSize(26);
            cv.drawText(ellipsize(artist, 320), 40, 96, p);

            // 控制键：上一首(52,131) 播放(170,131) 下一首(288,131)
            p.setColor(0xFF3A4048);
            float by = 131;
            tri(cv, p, 52, by, -1, 17);
            if (playing) {
                cv.drawRoundRect(new RectF(159, by - 17, 172, by + 17), 4, 4, p);
                cv.drawRoundRect(new RectF(183, by - 17, 196, by + 17), 4, 4, p);
            } else {
                tri(cv, p, 165, by, 1, 22);
            }
            tri(cv, p, 288, by, 1, 17);
            p.setFakeBoldText(false);
            cv.restore();
        }

        /** 播放/快进三角：dir=-1 朝左（上一首），1 朝右（播放/下一首）。 */
        private void tri(Canvas cv, Paint p, float cx, float cy, int dir, float r) {
            Path t = new Path();
            t.moveTo(cx - dir * r, cy - r);
            t.lineTo(cx - dir * r, cy + r);
            t.lineTo(cx + dir * r, cy);
            t.close();
            cv.drawPath(t, p);
        }

        private String ellipsize(String text, float max) {
            if (text == null) return "";
            p.setTextSize(34);
            if (p.measureText(text) <= max) return text;
            StringBuilder sb = new StringBuilder(text);
            while (sb.length() > 1 && p.measureText(sb + "…") > max) {
                sb.setLength(sb.length() - 1);
            }
            return sb + "…";
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            float x = e.getX() / s, y = e.getY() / s;
            MediaController c = cur;
            if (c == null) return true;
            try {
                MediaController.TransportControls tc = c.getTransportControls();
                if (hit(x, y, 52, 131, 34)) {
                    tc.skipToPrevious();
                } else if (hit(x, y, 177, 131, 36)) {
                    if (playing) tc.pause();
                    else tc.play();
                } else if (hit(x, y, 288, 131, 34)) {
                    tc.skipToNext();
                } else if (x >= 0 && x <= 532 && y >= 0 && y <= 180) {
                    // 卡片本体：点开正在播的应用（原车卡片是打开媒体中心）
                    Context ctx = getContext();
                    Intent i = ctx.getPackageManager()
                            .getLaunchIntentForPackage(c.getPackageName());
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(i);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "桌面音乐卡片按键: " + t);
            }
            return true;
        }

        private boolean hit(float x, float y, float cx, float cy, float r) {
            float dx = x - cx, dy = y - cy;
            return dx * dx + dy * dy <= r * r;
        }
    }
}
