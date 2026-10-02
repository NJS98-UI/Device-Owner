package com.ahui.clustercast;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * 录像回放（盯盯车播放器能力的自写版）：
 * 直接列 Storage 目录里的四宫格 mp4 和 锁定 子目录里的 mp4，点一个用系统 VideoView 播。
 * 不需要 FileProvider、不需要任何授权 —— 文件就在我们自己的存储目录里。
 * 文件不存在/解码不了就直说，不放假进度条。
 */
public class PlayerActivity extends Activity {

    private android.widget.VideoView video = null;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        getWindow().setStatusBarColor(0);
        String p = getIntent().getStringExtra("path");
        if (p != null) play(new File(p)); else showList();
    }

    // ---------- 列表 ----------

    private void showList() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24, 24, 24, 24);
        File dir = Storage.dir(this);
        ArrayList<File> files = new ArrayList<>();
        File[] list = dir.listFiles();
        if (list != null) {
            for (File f : list) if (f.isFile() && f.getName().endsWith(".mp4")) files.add(f);
        }
        File lock = new File(dir, "锁定");
        File[] lockList = lock.listFiles();
        if (lockList != null) {
            for (File f : lockList) if (f.isFile() && f.getName().endsWith(".mp4")) files.add(f);
        }
        Collections.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        if (files.isEmpty()) {
            android.widget.TextView t = Ui.text(this, 16, Color.WHITE, Typeface.NORMAL, 8);
            t.setText("还没有录像文件");
            box.addView(t);
        }
        for (final File f : files) {
            long mb = f.length() / 1024 / 1024;
            android.widget.TextView b = Ui.button(this, f.getName() + "（" + mb + "MB）", 13, false);
            Ui.click(b, new Runnable() { @Override public void run() { play(f); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 12;
            box.addView(b, lp);
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        setContentView(sc);
    }

    // ---------- 播放 ----------

    private void play(final File f) {
        if (!f.exists() || f.length() == 0L) {
            toast("文件不在了或没写完：" + f.getName());
            showList();
            return;
        }
        android.widget.VideoView v = new android.widget.VideoView(this);
        video = v;
        android.widget.MediaController mc = new android.widget.MediaController(this);
        v.setMediaController(mc);
        try {
            v.setVideoPath(f.getAbsolutePath());
        } catch (Throwable t) {
            toast("这个文件播不了：" + t.getClass().getSimpleName());
            showList();
            return;
        }
        v.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) {
                // 真能解码才开始，失败不装作在播。
                try { mp.start(); } catch (Throwable t) { toast("开始播放失败：" + t.getMessage()); }
            }
        });
        v.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                toast("这台机器解不了这个文件的编码");
                showList();
                return true;
            }
        });
        v.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer mp) { showList(); }
        });
        setContentView(v);
        v.start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (video != null) showList();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { if (video != null) video.stopPlayback(); } catch (Throwable t) { }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (video != null) video.stopPlayback(); } catch (Throwable t) { }
        video = null;
    }
}
