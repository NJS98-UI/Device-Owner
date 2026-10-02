package com.ahui.clustercast;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;

/**
 * 回放相册（盯盯车播放器 + EVCam 照片回放/多选删除的自写版）：
 * 视频/照片两页签；视频=四宫格 mp4 + 锁定 目录，点开用系统 VideoView 播；
 * 照片=目录里的 png，点开看大图。长按进多选，选完一次删。
 * 不需要 FileProvider、不需要任何授权 —— 文件就在我们自己的存储目录里。
 */
public class PlayerActivity extends Activity {

    private android.widget.VideoView video = null;
    /** v=视频 p=照片。 */
    private String tab = "v";
    /** 长按进多选，打勾的一次删掉。 */
    private boolean selecting = false;
    private final HashSet<File> sel = new HashSet<>();
    private LinearLayout listBox = null;
    private TextView barText = null;
    private TextView barDel = null;
    /** 大图查看中（返回键回列表用）。 */
    private boolean viewing = false;

    private static final Comparator<File> NEWEST = new Comparator<File>() {
        @Override public int compare(File a, File b) {
            return Long.compare(b.lastModified(), a.lastModified());
        }
    };

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        getWindow().setStatusBarColor(0);
        String p = getIntent().getStringExtra("path");
        if (p != null) play(new File(p)); else showList();
    }

    // ---------- 列表 ----------

    private ArrayList<File> files() {
        File dir = Storage.dir(this);
        ArrayList<File> r = new ArrayList<>();
        if (tab.equals("v")) {
            collect(r, dir, ".mp4");
            collect(r, new File(dir, "锁定"), ".mp4");
        } else {
            collect(r, dir, ".png");
        }
        Collections.sort(r, NEWEST);
        return r;
    }

    private void collect(ArrayList<File> r, File d, String suffix) {
        File[] ls = d.listFiles();
        if (ls != null)
            for (File f : ls) if (f.isFile() && f.getName().endsWith(suffix)) r.add(f);
    }

    private void showList() {
        viewing = false;
        video = null;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(24, 24, 24, 24);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView bv = Ui.button(this, "视频", 14, tab.equals("v"), 14, 7, Ui.R_ONOFF);
        TextView bp = Ui.button(this, "照片", 14, tab.equals("p"), 14, 7, Ui.R_ONOFF);
        TextView bk = Ui.button(this, "返回", 14, false, 14, 7, Ui.R_WHITE);
        Ui.click(bv, new Runnable() {
            @Override public void run() { if (!tab.equals("v")) { tab = "v"; resetSel(); showList(); } }
        });
        Ui.click(bp, new Runnable() {
            @Override public void run() { if (!tab.equals("p")) { tab = "p"; resetSel(); showList(); } }
        });
        Ui.click(bk, new Runnable() {
            @Override public void run() { finish(); }
        });
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        top.addView(bv, w);
        top.addView(bp, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ((LinearLayout.LayoutParams) bp.getLayoutParams()).leftMargin = 12;
        top.addView(bk, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ((LinearLayout.LayoutParams) bk.getLayoutParams()).leftMargin = 12;
        page.addView(top);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        barText = Ui.text(this, 13, 0xFFC6CED6, Typeface.NORMAL, 1);
        barText.setText("长按文件进多选，可一次删多个");
        bar.addView(barText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        barDel = Ui.button(this, "删除所选", 13, false, 12, 6, Ui.R_DANGER);
        Ui.click(barDel, new Runnable() {
            @Override public void run() { doDelete(); }
        });
        barDel.setVisibility(selecting ? View.VISIBLE : View.GONE);
        bar.addView(barDel, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ((LinearLayout.LayoutParams) barDel.getLayoutParams()).leftMargin = 12;
        page.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) bar.getLayoutParams()).topMargin = 16;

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        buildItems();
        ScrollView sc = new ScrollView(this);
        sc.addView(listBox);
        page.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);
    }

    private void resetSel() { selecting = false; sel.clear(); }

    private void buildItems() {
        listBox.removeAllViews();
        ArrayList<File> fs = files();
        if (fs.isEmpty()) {
            TextView t = Ui.text(this, 16, Color.WHITE, Typeface.NORMAL, 8);
            t.setText(tab.equals("v") ? "还没有录像文件" : "还没有照片");
            listBox.addView(t);
            return;
        }
        for (final File f : fs) {
            long mb = f.length() / 1024 / 1024;
            String mark = selecting && sel.contains(f) ? "[已选] " : "";
            TextView b = Ui.button(this,
                    mark + f.getName() + "（" + mb + "MB）", 13,
                    selecting && sel.contains(f), 12, 8, Ui.R_ONOFF);
            Ui.click(b, new Runnable() {
                @Override public void run() { tap(f); }
            });
            b.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    selecting = true;
                    sel.clear();
                    sel.add(f);
                    syncBar();
                    buildItems();
                    return true;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 12;
            listBox.addView(b, lp);
        }
    }

    private void tap(File f) {
        if (selecting) {
            if (sel.contains(f)) sel.remove(f); else sel.add(f);
            syncBar();
            buildItems();
            return;
        }
        if (tab.equals("v")) play(f); else view(f);
    }

    private void syncBar() {
        if (barText == null) return;
        barDel.setVisibility(selecting ? View.VISIBLE : View.GONE);
        barText.setText(selecting
                ? ("已选 " + sel.size() + " 个文件" + (sel.isEmpty() ? "，点了删除不生效" : ""))
                : "长按文件进多选，可一次删多个");
        barText.setTextColor(selecting ? Color.WHITE : 0xFFC6CED6);
    }

    private void doDelete() {
        if (!selecting || sel.isEmpty()) {
            toast("先长按文件进多选");
            return;
        }
        int n = 0;
        long bytes = 0L;
        for (File f : new ArrayList<>(sel)) {
            long b = f.length();
            if (f.delete()) { n++; bytes += b; }
        }
        toast("已删 " + n + " 个文件（" + (bytes / 1024 / 1024) + "MB）");
        resetSel();
        showList();
    }

    // ---------- 大图 ----------

    private void view(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= 1080) o.inSampleSize *= 2;
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (bmp == null) {
            toast("这张照片读不出来");
            return;
        }
        viewing = true;
        ImageView iv = new ImageView(this);
        iv.setBackgroundColor(Color.BLACK);
        iv.setImageBitmap(bmp);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Ui.click(iv, new Runnable() {
            @Override public void run() { showList(); }
        });
        setContentView(iv);
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
        if (video != null || viewing) showList();
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
