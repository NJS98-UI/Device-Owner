package com.jietu.clustercast;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * 音频播放弹窗 —— 完整复刻参考应用（云盘车机助手）自动化任务/日志映射里
 * 单击操作"播放音频"的配置弹窗（da.l3）：
 *   通道(AudioAttributes usage 0-100 逐个校验) / 音频焦点(无·降低其它·暂停其它) /
 *   播放时修改系统音量(通话·系统·铃声·媒体·闹钟·通知·MF·ACC·6~11) + 音量滑条 /
 *   扬声器输出设备(默认 + 系统输出设备枚举) / 音量增益(LoudnessEnhancer 0-80) /
 *   音源列表(内置 14 条 + 本机选择) + 试听/停止 + 确定。
 * 参考应用走它自家 carsdk 的 Car 通道音量（100+），我们没有那套 SDK，这一项不列。
 */
public class AudioPlayDialog extends Dialog {

    public static final int REQ_FILE = 7001;      // 选择音频文件
    public static final int REQ_DIR = 7002;       // 选择文件夹
    public static final int REQ_ANY = 7003;       // 选择普通文件（非音频照实拒绝）

    private static final String AUDIO_EXT = ".mp3 .wav .ogg .m4a .aac .flac .pcm";

    private static volatile AudioPlayDialog sActive;

    private final MainActivity act;
    private final String storeKey;
    private final String dlgTitle;
    private final GreetAudio.Cfg cfg;
    private final Runnable onChanged;

    private TextView tvChannel, tvFocus, tvVol, tvSpeaker;
    private SeekBar sbVol, sbGain;
    private LinearLayout fileList, advBody;
    private TextView btnAdv;
    private boolean advOpen;
    private TextView btnAudition;
    private String selectedSrc;
    private boolean auditioning;

    /** storeKey：门事件传事件号字符串（"0"~"9"），挡位传 "gear_1"~"gear_4"。 */
    public AudioPlayDialog(MainActivity a, String storeKey, String title,
            GreetAudio.Cfg c, Runnable onChanged) {
        super(a);
        this.act = a;
        this.storeKey = storeKey;
        this.dlgTitle = title;
        this.cfg = c != null ? c : new GreetAudio.Cfg();
        this.selectedSrc = this.cfg.src;
        this.onChanged = onChanged;
    }

    /** MainActivity.onActivityResult 转发入口。 */
    public static boolean handleResult(int req, Intent data) {
        AudioPlayDialog d = sActive;
        return d != null && d.onSafResult(req, data);
    }

    public static void closeActive() {
        AudioPlayDialog d = sActive;
        if (d != null) { try { d.dismiss(); } catch (Throwable ignored) { } }
    }

    @Override protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        sActive = this;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Context ctx = act;
        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Ui.darkBg(ctx, Ui.D_CARD, 14));
        int pad = Ui.dp(ctx, 16);
        page.setPadding(pad, pad, pad, pad);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(ctx, 17, Ui.D_TEXT, Typeface.BOLD, 1);
        title.setText("音频播放 · " + dlgTitle);
        bar.addView(title, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView btnClose = Ui.darkButton(ctx, "✕", 14, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(btnClose, new Runnable() { @Override public void run() { dismiss(); } });
        bar.addView(btnClose, Ui.ww());
        page.addView(bar, Ui.lw());
        page.addView(vsp(ctx, 10));

        // 音源：内置 14 条 + 本机音频，一行两个，紧凑不拉长
        TextView lblFiles = Ui.text(ctx, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        lblFiles.setText("音源（点一下选中）");
        page.addView(lblFiles, Ui.lw());
        page.addView(vsp(ctx, 6));
        fileList = new LinearLayout(ctx);
        fileList.setOrientation(LinearLayout.VERTICAL);
        ScrollView fsv = new ScrollView(ctx);
        fsv.setVerticalScrollBarEnabled(false);
        fsv.addView(fileList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(fsv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        page.addView(vsp(ctx, 10));

        // 选文件入口
        LinearLayout rowSel = new LinearLayout(ctx);
        rowSel.setOrientation(LinearLayout.HORIZONTAL);
        TextView bFile = Ui.darkButton(ctx, "选择音频文件", 13, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(bFile, new Runnable() { @Override public void run() { pickFile(false); } });
        rowSel.addView(bFile, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        rowSel.addView(hsp(ctx, 6));
        TextView bDir = Ui.darkButton(ctx, "选择文件夹", 13, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(bDir, new Runnable() { @Override public void run() { pickFolder(); } });
        rowSel.addView(bDir, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        rowSel.addView(hsp(ctx, 6));
        TextView bAny = Ui.darkButton(ctx, "选择普通文件", 13, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(bAny, new Runnable() { @Override public void run() { pickFile(true); } });
        rowSel.addView(bAny, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(rowSel, Ui.lw());
        page.addView(vsp(ctx, 8));

        // 高级设置（通道/焦点/音量/扬声器/增益）：默认收起，点一下展开
        advBody = new LinearLayout(ctx);
        advBody.setOrientation(LinearLayout.VERTICAL);
        buildAdvanced(ctx);
        advBody.setVisibility(View.GONE);
        btnAdv = Ui.darkButton(ctx, "高级设置 ▸", 13, Ui.D_BTN, Ui.D_TEXT_SUB);
        Ui.click(btnAdv, new Runnable() { @Override public void run() { toggleAdv(); } });
        page.addView(btnAdv, Ui.lw());
        page.addView(vsp(ctx, 6));
        page.addView(advBody, Ui.lw());
        page.addView(vsp(ctx, 8));

        // 试听/停止 + 确定
        LinearLayout rowOk = new LinearLayout(ctx);
        rowOk.setOrientation(LinearLayout.HORIZONTAL);
        btnAudition = Ui.darkButton(ctx, "试听", 14, Ui.D_GREEN, 0xFF102418);
        Ui.click(btnAudition, new Runnable() { @Override public void run() { toggleAudition(); } });
        rowOk.addView(btnAudition, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        rowOk.addView(hsp(ctx, 8));
        TextView btnOk = Ui.darkButton(ctx, "确定", 14, Ui.D_BTN_ON, 0xFFFFFFFF);
        Ui.click(btnOk, new Runnable() { @Override public void run() { save(); } });
        rowOk.addView(btnOk, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(rowOk, Ui.lw());

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(page, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        reloadFiles();
    }

    /** 高级项全部收进 advBody：通道/音频焦点/系统音量/音量大小/扬声器/音量增益。 */
    private void buildAdvanced(Context ctx) {
        // 通道（参考应用逐个 new AudioAttributes.setUsage(i) 校验，能建的就是合法通道）
        ArrayList<String[]> chOpts = new ArrayList<String[]>();
        int defCh = 1;
        for (int i = 0; i <= 100; i++) {
            try {
                new AudioAttributes.Builder().setUsage(i).build();
                chOpts.add(new String[]{"通道" + i, String.valueOf(i)});
            } catch (Throwable ignored) { }
        }
        tvChannel = optRow(ctx, advBody, "通道", chOpts, cfg.channel,
                new OptCb() { @Override public void pick(int v) { cfg.channel = v; } });
        if (findVal(chOpts, cfg.channel) < 0) cfg.channel = defCh;

        // 音频焦点
        ArrayList<String[]> foOpts = new ArrayList<String[]>();
        foOpts.add(new String[]{"无", "-1"});
        foOpts.add(new String[]{"播放时暂停其它音频", "2"});
        foOpts.add(new String[]{"播放时降低其它音量", "3"});
        tvFocus = optRow(ctx, advBody, "音频焦点", foOpts, cfg.focus,
                new OptCb() { @Override public void pick(int v) { cfg.focus = v; } });

        // 播放时修改音量
        ArrayList<String[]> voOpts = new ArrayList<String[]>();
        voOpts.add(new String[]{"不修改系统音量", "-1"});
        voOpts.add(new String[]{"播放时修改音量(通话音量)", "0"});
        voOpts.add(new String[]{"播放时修改音量(系统音量)", "1"});
        voOpts.add(new String[]{"播放时修改音量(铃声音量)", "2"});
        voOpts.add(new String[]{"播放时修改音量(媒体音量)", "3"});
        voOpts.add(new String[]{"播放时修改音量(闹钟音量)", "4"});
        voOpts.add(new String[]{"播放时修改音量(通知音量)", "5"});
        voOpts.add(new String[]{"播放时修改音量(MF音量)", "8"});
        if (android.os.Build.VERSION.SDK_INT >= 26)
            voOpts.add(new String[]{"播放时修改音量(ACC音量)", "10"});
        voOpts.add(new String[]{"播放时修改音量(6)", "6"});
        voOpts.add(new String[]{"播放时修改音量(7)", "7"});
        voOpts.add(new String[]{"播放时修改音量(9)", "9"});
        voOpts.add(new String[]{"播放时修改音量(11)", "11"});
        tvVol = optRow(ctx, advBody, "系统音量", voOpts, cfg.volType,
                new OptCb() { @Override public void pick(int v) { cfg.volType = v; } });
        sbVol = sliderRow(ctx, advBody, "音量大小", 100, cfg.volVal,
                new SeekCb() { @Override public void change(int p) { cfg.volVal = p; } });

        // 扬声器输出设备
        ArrayList<String[]> spOpts = new ArrayList<String[]>();
        spOpts.add(new String[]{"默认", "-1"});
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo d : devs) {
                String name = String.valueOf(d.getProductName()) + "(" + d.getId() + ")";
                spOpts.add(new String[]{name, String.valueOf(d.getId())});
            }
        } catch (Throwable ignored) { }
        tvSpeaker = optRow(ctx, advBody, "扬声器", spOpts, cfg.speaker,
                new OptCb() { @Override public void pick(int v) { cfg.speaker = v; } });

        // 音量增益（LoudnessEnhancer，参考应用 targetGain = progress*100 mB）
        sbGain = sliderRow(ctx, advBody, "音量增益", 80, cfg.gain / 100,
                new SeekCb() { @Override public void change(int p) { cfg.gain = p * 100; } });
    }

    private void toggleAdv() {
        advOpen = !advOpen;
        advBody.setVisibility(advOpen ? View.VISIBLE : View.GONE);
        btnAdv.setText(advOpen ? "高级设置 ▾" : "高级设置 ▸");
    }

    @Override public void dismiss() {
        GreetPlayer.stop();
        if (sActive == this) sActive = null;
        super.dismiss();
    }

    // ---------- 音源列表 ----------

    private void reloadFiles() {
        fileList.removeAllViews();
        final Context ctx = act;
        ArrayList<String[]> items = new ArrayList<String[]>();
        for (final GreetAudio.Item it : GreetAudio.BUILTIN)
            items.add(new String[]{it.label, "raw:" + it.name});
        File dir = userDir(ctx);
        File[] fs = dir.listFiles();
        if (fs != null) {
            java.util.Arrays.sort(fs);
            for (final File f : fs) {
                if (!f.isFile() || !isAudio(f.getName())) continue;
                items.add(new String[]{f.getName(), "file:" + f.getAbsolutePath()});
            }
        }
        // 一行两个，界面紧凑
        for (int i = 0; i < items.size(); i += 2) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(fileChip(ctx, items.get(i)[0], items.get(i)[1]),
                    Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i + 1 < items.size()) {
                row.addView(hsp(ctx, 6));
                row.addView(fileChip(ctx, items.get(i + 1)[0], items.get(i + 1)[1]),
                        Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            fileList.addView(row, Ui.lw());
            fileList.addView(vsp(ctx, 4));
        }
    }

    private TextView fileChip(final Context ctx, String label, final String src) {
        TextView tv = Ui.darkButton(ctx, label, 12,
                selectedSrc != null && selectedSrc.equals(src) ? Ui.D_BTN_ON : Ui.D_BTN,
                selectedSrc != null && selectedSrc.equals(src) ? 0xFFFFFFFF : Ui.D_TEXT);
        Ui.click(tv, new Runnable() { @Override public void run() {
            selectedSrc = src;
            reloadFiles();
        }});
        return tv;
    }

    // ---------- 试听 / 保存 ----------

    private void toggleAudition() {
        if (auditioning) {
            GreetPlayer.stop();
            auditioning = false;
            btnAudition.setText("试听");
            return;
        }
        GreetAudio.Src s = GreetAudio.resolve(act, selectedSrc);
        if (s == null) {
            act.greetNote("先在列表里选一个音频", false);
            return;
        }
        auditioning = GreetPlayer.play(act, cfg, s.resId, s.path, new GreetPlayer.Done() {
            @Override public void onDone() {
                act.runOnUiThread(new Runnable() { @Override public void run() {
                    auditioning = false;
                    btnAudition.setText("试听");
                }});
            }
        });
        btnAudition.setText(auditioning ? "停止" : "试听");
    }

    private void save() {
        cfg.src = selectedSrc != null ? selectedSrc : "";
        new Cfg(act).setAudio(storeKey, GreetAudio.toJson(cfg));
        if (onChanged != null) onChanged.run();
        dismiss();
    }

    // ---------- 系统选择器（SAF） ----------

    private void pickFile(boolean any) {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType(any ? "*/*" : "audio/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            act.startActivityForResult(
                    Intent.createChooser(i, "选择音频文件"), any ? REQ_ANY : REQ_FILE);
        } catch (Throwable t) {
            act.greetNote("打不开系统文件选择器：" + t.getClass().getSimpleName(), false);
        }
    }

    private void pickFolder() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            act.startActivityForResult(i, REQ_DIR);
        } catch (Throwable t) {
            act.greetNote("打不开文件夹选择器：" + t.getClass().getSimpleName(), false);
        }
    }

    private boolean onSafResult(int req, Intent data) {
        if (data == null || data.getData() == null) return req >= REQ_FILE && req <= REQ_ANY;
        Uri uri = data.getData();
        if (req == REQ_DIR) { importFolder(uri); return true; }
        importOne(uri, req == REQ_ANY);
        return true;
    }

    private void importOne(Uri uri, boolean any) {
        try {
            String name = displayName(uri);
            if (name == null || name.isEmpty()) name = "audio_" + System.currentTimeMillis() + ".mp3";
            if (!isAudio(name)) {
                act.greetNote(any ? "这不是音频文件：" + name : "选的文件不是音频：" + name, false);
                return;
            }
            File dst = new File(userDir(act), name);
            copyStream(act.getContentResolver().openInputStream(uri), dst);
            selectedSrc = "file:" + dst.getAbsolutePath();
            cfg.src = selectedSrc;
            reloadFiles();
            act.greetNote("已导入 " + name, true);
        } catch (Throwable t) {
            act.greetNote("导入失败：" + t.getClass().getSimpleName() + " " + t.getMessage(), false);
        }
    }

    private void importFolder(Uri tree) {
        try {
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            android.database.Cursor c = act.getContentResolver().query(kids,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            int n = 0;
            if (c != null) {
                while (c.moveToNext()) {
                    String name = c.getString(0);
                    if (name == null || !isAudio(name)) continue;
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree,
                            DocumentsContract.getTreeDocumentId(tree) + "/" + name);
                    try {
                        File dst = new File(userDir(act), name);
                        copyStream(act.getContentResolver().openInputStream(doc), dst);
                        n++;
                    } catch (Throwable ignored) { }
                }
                c.close();
            }
            if (n == 0) {
                act.greetNote("这个文件夹里没有音频文件", false);
            } else {
                reloadFiles();
                act.greetNote("从文件夹导入 " + n + " 个音频", true);
            }
        } catch (Throwable t) {
            act.greetNote("读文件夹失败：" + t.getClass().getSimpleName(), false);
        }
    }

    // ---------- 小工具 ----------

    private static File userDir(Context c) {
        File d = new File(c.getFilesDir(), "greet_user");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static boolean isAudio(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot).toLowerCase();
        for (String e : AUDIO_EXT.split(" ")) if (e.equals(ext)) return true;
        return false;
    }

    private String displayName(Uri uri) {
        try {
            android.database.Cursor c = act.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    String n = c.getString(0);
                    c.close();
                    return n;
                }
                c.close();
            }
        } catch (Throwable ignored) { }
        String seg = uri.getLastPathSegment();
        return seg == null ? null : seg.substring(seg.lastIndexOf('/') + 1);
    }

    private static void copyStream(InputStream in, File dst) throws Exception {
        OutputStream out = null;
        try {
            out = new java.io.FileOutputStream(dst);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }

    // ---------- 选项行 / 弹层 ----------

    private interface OptCb { void pick(int v); }
    private interface SeekCb { void change(int p); }

    private TextView optRow(Context ctx, LinearLayout page, String label,
                            final ArrayList<String[]> opts, int cur, final OptCb cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView lbl = Ui.text(ctx, 13, Ui.D_TEXT_SUB, Typeface.NORMAL, 1);
        lbl.setText(label);
        row.addView(lbl, new LinearLayout.LayoutParams(Ui.dp(ctx, 88),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(hsp(ctx, 6));
        int idx = findVal(opts, cur);
        final String curLabel = idx >= 0 ? opts.get(idx)[0] : opts.get(0)[0];
        final int curVal = idx >= 0 ? cur : Integer.parseInt(opts.get(0)[1]);
        TextView val = Ui.darkButton(ctx, curLabel, 13, Ui.D_BTN, Ui.D_TEXT);
        val.setGravity(Gravity.CENTER);
        Ui.click(val, new Runnable() { @Override public void run() {
            showOptions(label, opts, val, cb);
        }});
        val.setTag(curVal);
        row.addView(val, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(row, Ui.lw());
        page.addView(vsp(ctx, 6));
        return val;
    }

    private static int findVal(ArrayList<String[]> opts, int v) {
        for (int i = 0; i < opts.size(); i++)
            if (Integer.parseInt(opts.get(i)[1]) == v) return i;
        return -1;
    }

    private void showOptions(String title, final ArrayList<String[]> opts,
                             final TextView target, final OptCb cb) {
        final Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Context ctx = act;
        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Ui.darkBg(ctx, Ui.D_CARD, 14));
        int pad = Ui.dp(ctx, 14);
        page.setPadding(pad, pad, pad, pad);
        TextView t = Ui.text(ctx, 15, Ui.D_TEXT, Typeface.BOLD, 1);
        t.setText("选择" + title);
        page.addView(t, Ui.lw());
        page.addView(vsp(ctx, 8));
        ScrollView sv = new ScrollView(ctx);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        for (final String[] o : opts) {
            TextView b = Ui.darkButton(ctx, o[0], 13, Ui.D_BTN, Ui.D_TEXT);
            Ui.click(b, new Runnable() { @Override public void run() {
                int v = Integer.parseInt(o[1]);
                cb.pick(v);
                target.setText(o[0]);
                target.setTag(v);
                d.dismiss();
            }});
            list.addView(b, Ui.lw());
            list.addView(vsp(ctx, 4));
        }
        sv.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 340)));
        d.setContentView(page, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        d.show();
        android.view.WindowManager.LayoutParams lp = d.getWindow().getAttributes();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        d.getWindow().setAttributes(lp);
    }

    private SeekBar sliderRow(Context ctx, LinearLayout page, String label, int max,
                              int cur, final SeekCb cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView lbl = Ui.text(ctx, 13, Ui.D_TEXT_SUB, Typeface.NORMAL, 1);
        lbl.setText(label);
        row.addView(lbl, new LinearLayout.LayoutParams(Ui.dp(ctx, 88),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(hsp(ctx, 6));
        SeekBar sb = new SeekBar(ctx);
        sb.setMax(max);
        sb.setProgress(Math.max(0, Math.min(max, cur)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                cb.change(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        row.addView(sb, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(row, Ui.lw());
        page.addView(vsp(ctx, 6));
        return sb;
    }

    private static View vsp(Context c, int dpH) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, dpH)));
        return v;
    }

    private static View hsp(Context c, int dpW) {
        // 高度固定 1px：普通 View 的 WRAP 高度在 AT_MOST 下会撑满（本项目已知坑）
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, dpW), 1));
        return v;
    }
}
