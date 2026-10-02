package com.ahui.clustercast;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 仪表屏（display 2）专用极简页：只显示"正在放什么歌"，不搬任何第三方 App 的界面。
 * 音乐 App 那份实例留在主屏，所以点它照常打开、投屏互不影响。
 */
public class ClusterActivity extends Activity {

    private static final int GREEN = 0xFF61C96B;

    private static final List<ClusterActivity> ALIVE = new ArrayList<>();

    /** 三指右滑退出时由服务调用：把这些实例收掉，仪表屏还给原车画面。 */
    public static void closeAll() {
        List<ClusterActivity> copy;
        synchronized (ALIVE) { copy = new ArrayList<>(ALIVE); ALIVE.clear(); }
        for (ClusterActivity a : copy) {
            try { a.finish(); } catch (Throwable ignored) { }
        }
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private Tick tick;

    private ImageView cover;
    private TextView title, artist, line, tvPos, tvDur;
    private View fill, rest;
    private String lastKey = "";

    /** 静态嵌套类：捕获 this 的匿名内部类会让本项目的 d8 崩掉。 */
    private static class Tick implements Runnable {
        final WeakReference<ClusterActivity> ref;
        Tick(ClusterActivity a) { ref = new WeakReference<>(a); }
        @Override public void run() {
            ClusterActivity a = ref.get();
            if (a == null) return;
            a.refresh();
            a.h.postDelayed(this, 400);
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(0x00000080 /* FLAG_KEEP_SCREEN_ON */);
        setContentView(build());
        synchronized (ALIVE) { ALIVE.add(this); }
        tick = new Tick(this);
        h.postDelayed(tick, 0);
    }

    @Override protected void onDestroy() {
        h.removeCallbacks(tick);
        synchronized (ALIVE) { ALIVE.remove(this); }
        super.onDestroy();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(48), dp(24), dp(48), dp(24));

        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(300), dp(300));
        clp.gravity = Gravity.CENTER_VERTICAL;
        root.addView(cover, clp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = dp(40);
        colLp.gravity = Gravity.CENTER_VERTICAL;

        title = text(56, Color.WHITE, Typeface.BOLD);
        title.setMaxLines(2);
        col.addView(title, matchWrap());

        artist = text(34, 0xFFBFBFBF, Typeface.NORMAL);
        artist.setMaxLines(1);
        LinearLayout.LayoutParams alp = matchWrap();
        alp.topMargin = dp(10);
        col.addView(artist, alp);

        // 车机字体没有 █░ 这类方块字形，进度条只能用真 View 画
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        tvPos = text(26, GREEN, Typeface.NORMAL);
        row.addView(tvPos, wrapContent());
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackgroundColor(0xFF2A2A2A);
        fill = new View(this);
        fill.setBackgroundColor(GREEN);
        rest = new View(this);
        track.addView(fill, weighted(1f, dp(10)));
        track.addView(rest, weighted(0f, dp(10)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(18);
        tlp.rightMargin = dp(18);
        row.addView(track, tlp);
        tvDur = text(26, GREEN, Typeface.NORMAL);
        row.addView(tvDur, wrapContent());
        LinearLayout.LayoutParams rlp = matchWrap();
        rlp.topMargin = dp(26);
        col.addView(row, rlp);

        line = text(24, 0xFF7A7A7A, Typeface.NORMAL);
        LinearLayout.LayoutParams llp = matchWrap();
        llp.topMargin = dp(10);
        col.addView(line, llp);

        root.addView(col, colLp);
        return root;
    }

    private LinearLayout.LayoutParams weighted(float w, int h) {
        return new LinearLayout.LayoutParams(0, h, w);
    }

    private LinearLayout.LayoutParams wrapContent() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(int sp, int color, int style) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void refresh() {
        MediaController c = MusicListener.inst() == null ? null : MusicListener.inst().active();
        MediaMetadata md = MusicListener.meta(c);

        if (c == null || md == null) {
            String tip = MusicListener.ready()
                    ? "没有正在播放的音乐" : "未获得通知使用权，读不到播放信息";
            show(tip, "", 0, 0, null, null);
            return;
        }

        String name = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        String who = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (who == null) who = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
        String src = label(c.getPackageName());
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);

        long pos = MusicListener.position(c);
        long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String key = src + "|" + name + "|" + who + "|" + (pos / 1000);
        if (key.equals(lastKey)) return;
        lastKey = key;
        show(name == null ? "未知歌曲" : name, who == null ? "" : who, pos, dur, src, art);
    }

    private void show(String t, String a, long pos, long dur, String src, Bitmap art) {
        title.setText(t);
        artist.setText(a);
        boolean hasBar = dur > 0;
        tvPos.setVisibility(hasBar ? View.VISIBLE : View.GONE);
        tvDur.setVisibility(hasBar ? View.VISIBLE : View.GONE);
        ((LinearLayout.LayoutParams) fill.getLayoutParams()).weight = hasBar ? pos : 0f;
        ((LinearLayout.LayoutParams) rest.getLayoutParams()).weight =
                hasBar ? Math.max(0f, dur - pos) : 1f;
        fill.requestLayout();
        tvPos.setText(hasBar ? fmt(pos) : "");
        tvDur.setText(hasBar ? fmt(dur) : "");
        line.setText(src == null ? "" : "来源：" + src);
        if (art != null) cover.setImageBitmap(art);
        else cover.setImageDrawable(null);
    }

    /** 包名换成应用名，仪表屏上不用露 com.xxx。 */
    private String label(String pkg) {
        if (pkg == null) return null;
        try {
            android.content.pm.ApplicationInfo ai =
                    getPackageManager().getApplicationInfo(pkg, 0);
            return getPackageManager().getApplicationLabel(ai).toString();
        } catch (Throwable t) { return pkg; }
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
