package com.ahui.clustercast;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/** 智能控制中心：投屏 / 空调 / 车窗尾门 / 倒车 / 记录仪 五个模块，底部页签切换。 */
public class MainActivity extends Activity implements CastService.LogSink {

    private final Handler handler = new Handler();
    private Cfg cfg;
    private TextView log;
    private final ArrayList<String> uiLog = new ArrayList<>();
    private Refresh refreshLoop;

    private FrameLayout content = null;
    private final View[] pages = new View[5];
    private final ArrayList<TextView> tabs = new ArrayList<>();
    private int cur = 0;
    private TextView busChip;
    private TextView carChip;
    private boolean askedPerm = false;

    // 投屏页开关
    private TextView btnNavi;
    private TextView btnSimple;
    private TextView btnFollow;
    private TextView btnGuard;
    private TextView btnFill;
    private TextView btnDock;
    private TextView btnTop;
    private TextView btnMirror;

    // 车窗/尾门
    private final ArrayList<TextView> winBtns = new ArrayList<>();
    private final ArrayList<TextView> winState = new ArrayList<>();
    private boolean winBusy = false;
    private TextView tailBtn;
    private boolean tailBusy = false;
    private boolean tailOpenState = false;

    // 倒车（后视镜下翻）
    private int revSel = 1;
    private TextView revOn;
    private TextView revGear;
    private TextView mirNorm;
    private TextView mirDownB;
    private boolean mirBusy = false;
    private int mirNormReadL = -1;
    private int mirNormReadR = -1;
    private int mirDownReadL = -1;
    private int mirDownReadR = -1;
    private LinearLayout revCamHost = null;

    // 记录仪
    private TextView recChip;
    private TextView recPause;
    private TextView recSeg;
    private TextView storLine;
    private TextView btnSound;
    private TextView btnWm;
    private TextView btnGearRec;
    private TextView btnEmg;
    private final ArrayList<TextView> qBtns = new ArrayList<>();
    private final ArrayList<TextView> loopBtns = new ArrayList<>();
    private final ArrayList<TextView> picBtns = new ArrayList<>();
    private TextView btnBootRec;
    private TextView btnTone;
    private TextView btnCap;
    /** EVCam 移植功能：息屏录制、悬浮钮、参录摄像头、照片上限。 */
    private TextView btnScrOff;
    private TextView btnFloat;
    private final ArrayList<TextView> maskBtns = new ArrayList<>();
    private TextView btnPhotoCap;
    private LinearLayout dvrCamHost = null;
    private View dvrPanel = null;
    private TextView btnDvrSet = null;
    private boolean dvrSetOn = false;
    private String dvrSelected = CamCtl.ID_FRONT;
    private int camOwner = -1;
    private boolean askedCam = false;
    private boolean gearBusy = false;

    /** 弱引用回环：Activity 没了自动停。 */
    private static class Refresh implements Runnable {
        private final WeakReference<MainActivity> ref;
        Refresh(MainActivity a) { ref = new WeakReference<>(a); }
        @Override public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            a.refresh();
            a.handler.postDelayed(this, 1500);
        }
    }

    private interface SvcBody { void run(CastService svc); }

    private void withSvc(SvcBody b) {
        CastService svc = CastService.inst();
        if (svc == null) { toast("后台服务还在启动，稍等一下"); return; }
        b.run(svc);
    }

    /** 需要同步拿返回值时用；服务没起来返回 null。 */
    private CastService svc0() { return CastService.inst(); }

    // ---------- 生命周期 ----------

    @Override
    protected void onCreate(Bundle b) {
        Ui.fit1050(this);
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        cfg = new Cfg(this);
        setContentView(build());
        CastService.start(this);
        refreshLoop = new Refresh(this);
        handler.postDelayed(refreshLoop, 300);
        slog("服务已启动，正在监听三指手势广播");
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.postDelayed(attach, 600);
        handler.postDelayed(new Runnable() {
            @Override public void run() { autoPermCheck(); }
        }, 1200);
        handler.postDelayed(new Runnable() {
            @Override public void run() { if (onCamPage()) ensureCamPerm(); }
        }, 900);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(attach);
        CastService inst = CastService.inst();
        if (inst != null) inst.setSink(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshLoop);
        CastService inst = CastService.inst();
        if (inst != null) inst.setSink(null);
        super.onDestroy();
    }

    private final Runnable attach = new Runnable() {
        @Override public void run() {
            CastService svc = CastService.inst();
            if (svc == null) { CastService.start(MainActivity.this); return; }
            svc.setSink(MainActivity.this);
            takeCamsForPage(cur);
            refresh();
        }
    };

    @Override public void onLog(String s) { handler.post(new Runnable() {
        @Override public void run() { renderLog(); }
    }); }

    /** 界面操作也记进同一个日志，格式和投屏日志一致。 */
    private void slog(String s) {
        String t = new SimpleDateFormat("HH:mm:ss").format(new Date());
        uiLog.add(t + "  " + s);
        while (uiLog.size() > 80) uiLog.remove(0);
        renderLog();
    }

    private void renderLog() {
        if (log == null) return;
        StringBuilder sb = new StringBuilder();
        CastService inst = CastService.inst();
        if (inst != null) {
            String lt = inst.logText();
            if (lt != null) sb.append(lt).append('\n');
        }
        for (String l : uiLog) sb.append(l).append('\n');
        log.setText(sb);
        android.view.ViewParent p = log.getParent();
        if (p instanceof ScrollView) {
            final ScrollView sv = (ScrollView) p;
            sv.post(new Runnable() {
                @Override public void run() { sv.fullScroll(View.FOCUS_DOWN); }
            });
        }
    }

    // ---------- 整体骨架 ----------

    private View build() {
        FrameLayout shell = new FrameLayout(this);
        shell.setBackground(Ui.wallpaper(this));
        shell.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        shell.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        col.addView(header(), Ui.lw());

        content = new FrameLayout(this);
        LinearLayout.LayoutParams clp = Ui.lw();
        clp.weight = 1f;
        clp.topMargin = Ui.dp(this, 10);
        col.addView(content, clp);
        pages[0] = pageCast();
        pages[1] = null;                       // 空调走原车，不占页
        pages[2] = pageWindow();
        pages[3] = pageReverse();
        pages[4] = pageDvr();
        for (View p : pages) {
            if (p != null)
                content.addView(p, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        LinearLayout.LayoutParams barWrap = Ui.lw();
        barWrap.topMargin = Ui.dp(this, 10);
        barWrap.height = Ui.dp(this, 52);
        col.addView(tabBar(), barWrap);
        showPage(0);
        return shell;
    }

    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackground(Ui.paint(this, Ui.R_WHITE, 14));
        h.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        TextView t = Ui.text(this, 19, Ui.INK, Typeface.BOLD, 1);
        t.setText("智能控制中心");
        h.addView(t, Ui.ww());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1, 1f);
        h.addView(new View(this), sp);
        busChip = chip(h, "总线检测中");
        carChip = chip(h, "仪表屏检测中");
        return h;
    }

    private TextView chip(LinearLayout parent, String s) {
        TextView c = Ui.text(this, 11, Ui.INK_SUB, Typeface.NORMAL, 1);
        c.setText(s);
        c.setGravity(Gravity.CENTER);
        c.setBackground(Ui.paint(this, Ui.R_CHIP, 13));
        c.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5));
        LinearLayout.LayoutParams p = Ui.ww();
        p.rightMargin = Ui.dp(this, 8);
        parent.addView(c, p);
        return c;
    }

    /** 底部页签条：五个等宽，整条铺满，当前页白底蓝字。 */
    private View tabBar() {
        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Ui.paint(this, Ui.R_WHITE, 16));
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5));
        for (int i = 0; i < Presets.ITEMS.size(); i++) {
            final int idx = i;
            TextView b = Ui.text(this, 13, Ui.INK_SUB, Typeface.NORMAL, 1);
            b.setGravity(Gravity.CENTER);
            b.setText(Presets.ITEMS.get(i).name);
            b.setClickable(true);
            Ui.click(b, new Runnable() {
                @Override public void run() { onTab(idx); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            tabs.add(b);
            bar.addView(b, lp);
        }
        return bar;
    }

    private void onTab(int i) {
        if (i == 1) {   // 空调：以原车界面为准，直接拉起，不切页
            String err = Presets.launch(this, Presets.ITEMS.get(1));
            if (err != null) toast(err);
            slog("切换至 空调 页面");
            return;
        }
        if (cur != i) slog("切换至 " + Presets.ITEMS.get(i).name + " 页面");
        showPage(i);
    }

    private void showPage(int i) {
        cur = i;
        for (int k = 0; k < pages.length; k++) {
            if (pages[k] != null)
                pages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
        }
        takeCamsForPage(i);
    }

    /**
     * 切页时按「当前页需要的集合」摘掉不该留的镜头。
     * 倒车页要 4/5/6（LEFT/FRONT/RIGHT），记录仪页要 4/5/6/7，
     * 两边都抢前摄（id4）。别让两页同时握着同一路。
     *
     * 后台录像：离开记录仪页不再自动停录。
     */
    private void takeCamsForPage(int i) {
        final CastService svc = CastService.inst();
        if (svc == null) return;
        if (camOwner == i) return;
        camOwner = i;
        switch (i) {
            case 3:
                svc.camsDetachAllExcept(new String[]{
                        CamCtl.ID_LEFT, CamCtl.ID_FRONT, CamCtl.ID_RIGHT});
                buildRevCams();
                break;
            case 4:
                svc.camsDetachAllExcept(new String[]{
                        CamCtl.ID_FRONT, CamCtl.ID_BACK,
                        CamCtl.ID_LEFT, CamCtl.ID_RIGHT});
                buildDvrCams();
                ensureCamPerm();
                break;
            default:
                svc.camsDetachAllExcept(new String[]{});
                break;
        }
    }

    private boolean onCamPage() { return cur == 3 || cur == 4; }

    /** CAMERA + RECORD_AUDIO 运行时授权：只有进了会用到镜头的页才弹。 */
    private void ensureCamPerm() {
        CastService svc = CastService.inst();
        if (svc != null && svc.cams().granted()) { svc.cams().restart(); return; }
        if (askedCam) return;
        askedCam = true;
        slog("记录仪需要摄像头权限（带声音还要录音权限），弹出系统授权框");
        try {
            requestPermissions(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO}, REQ_CAM);
        } catch (Throwable t) {
            slog("弹不出授权框：" + t.getClass().getSimpleName() + "（这台车可能没有授权界面）");
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        if (code != REQ_CAM) return;
        boolean cam = false;
        boolean mic = false;
        for (int i = 0; i < perms.length; i++) {
            if (i >= grants.length) continue;
            if (perms[i].equals(Manifest.permission.CAMERA))
                cam = grants[i] == PackageManager.PERMISSION_GRANTED;
            if (perms[i].equals(Manifest.permission.RECORD_AUDIO))
                mic = grants[i] == PackageManager.PERMISSION_GRANTED;
        }
        if (cam)
            slog("摄像头权限已给，正在接四路" +
                    (!mic ? "（没给录音权限：录像不带声音）" : ""));
        else
            slog("摄像头权限被拒：画面和录像都用不了");
        if (cam) {
            CastService svc = CastService.inst();
            if (svc != null) svc.cams().restart();
        }
    }

    // ---------- 投屏页 ----------

    private View pageCast() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setFillViewport(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        row.addView(leftScroll, slp);
        final LinearLayout left = Ui.card(this, 16);
        leftScroll.addView(left, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        btnNavi = sBtn("导航模式", new Runnable() {
            @Override public void run() { pickTheme(Vd.THEME_NAVI); }
        });
        btnSimple = sBtn("极简模式", new Runnable() {
            @Override public void run() { pickTheme(Vd.THEME_SIMPLE); }
        });
        btnFollow = sBtn("跟随前台", new Runnable() {
            @Override public void run() {
                boolean on = !cfg.followTop();
                cfg.setFollowTop(on);
                if (on && !TopApp.granted(MainActivity.this)) toast(USAGE_ADB_HINT);
                syncToggles();
            }
        });
        btnGuard = sBtn("顶掉管理员弹窗", new Runnable() {
            @Override public void run() {
                boolean on = !cfg.guard();
                cfg.setGuard(on);
                AdminGuard.reset();
                syncToggles();
            }
        });
        btnFill = sBtn("整屏填充", new Runnable() {
            @Override public void run() {
                boolean on = !cfg.castFill();
                cfg.setCastFill(on);
                slog("投屏整屏填充 -> " + (on ? "开（尺寸由我们写成仪表整屏）" : "关（沿用系统区域）"));
                syncToggles();
            }
        });
        btnDock = sBtn("沉浸dock", new Runnable() {
            @Override public void run() {
                boolean on = !cfg.dockImmersive();
                if (on && !Dock.canWrite(MainActivity.this)) {
                    slog("沉浸dock 开不了：没授权，电脑上执行一次 adb shell pm grant " +
                            getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
                    syncToggles();
                } else {
                    cfg.setDockImmersive(on);
                    withSvc(new SvcBody() {
                        @Override public void run(CastService svc) { svc.dockApply(on); }
                    });
                    syncToggles();
                }
            }
        });
        btnTop = sBtn("强制顶层", new Runnable() {
            @Override public void run() {
                boolean on = !cfg.forceTop();
                cfg.setForceTop(on);
                slog("强制顶层 -> " + (on ?
                        "开（仪表页被压住就自动重拉回顶层）" : "关（被压住就让它压着）"));
                syncToggles();
            }
        });
        btnMirror = sBtn("整屏镜像", new Runnable() {
            @Override public void run() {
                boolean on = !cfg.mirrorMode();
                cfg.setMirrorMode(on);
                slog(on ? "整屏镜像 -> 开（三指左滑投主屏整屏，第一次会弹系统录屏授权）"
                         : "整屏镜像 -> 关（回到原来的按应用投屏）");
                syncToggles();
            }
        });
        TextView restart = sBtnRestart();

        left.addView(btnRow(
                sBtn("选择投屏应用", new Runnable() {
                    @Override public void run() { openPicker(); }
                }),
                sBtn("立即投屏", new Runnable() {
                    @Override public void run() { withSvc(new SvcBody() {
                        @Override public void run(CastService svc) { svc.castNow(); }
                    }); }
                }),
                sBtn("退出投屏", new Runnable() {
                    @Override public void run() { withSvc(new SvcBody() {
                        @Override public void run(CastService svc) { svc.exitNow(); }
                    }); }
                })), Ui.lw());
        left.addView(btnRow(btnNavi, btnSimple, btnFollow), withMargin(Ui.lw(), Ui.dp(this, 6)));
        left.addView(btnRow(btnGuard, btnFill, btnDock), withMargin(Ui.lw(), Ui.dp(this, 6)));
        left.addView(btnRowN(4, btnTop, btnMirror,
                sBtn("使用情况授权", new Runnable() {
                    @Override public void run() {
                        if (!TopApp.request(MainActivity.this)) toast(USAGE_ADB_HINT);
                    }
                }),
                sBtn("通知使用权", new Runnable() {
                    @Override public void run() {
                        if (!openNotifSettings()) toast(NOTIF_ADB_HINT);
                    }
                })),
                withMargin(Ui.lw(), Ui.dp(this, 6)));
        left.addView(btnRowN(4, restart, sBtnCluster(), sBtnSniff(), sBtnProj()),
                withMargin(Ui.lw(), Ui.dp(this, 6)));
        LinearLayout.LayoutParams spn = Ui.lw();
        spn.height = 0;
        spn.weight = 1f;
        left.addView(new View(this), spn);

        LinearLayout right = Ui.card(this, 16);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f);
        rp.leftMargin = Ui.dp(this, 10);
        row.addView(right, rp);
        log = Ui.text(this, 11, Ui.INK_SUB, Typeface.NORMAL, 0);
        log.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        ScrollView lsv = new ScrollView(this);
        lsv.addView(log);
        LinearLayout.LayoutParams llp = Ui.lw();
        llp.topMargin = Ui.dp(this, 8);
        llp.height = 0;
        llp.weight = 1f;
        right.addView(lsv, llp);
        return row;
    }

    // ---------- 车窗/尾门页 ----------

    private View pageWindow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout lc = Ui.card(this, 16);
        ScrollView lsv = new ScrollView(this);
        lsv.setFillViewport(true);
        lsv.addView(lc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(lsv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        lc.addView(carImage(R.drawable.car_model, Ui.dp(this, 120)), Ui.lw());
        winBtns.clear();
        String[] names = {"透气", "全开", "全关"};
        winState.clear();
        for (int c = 0; c <= 2; c++) {
            final int ci = c;
            TextView b = Ui.button(this, names[c], 14, false, 8, 0,
                    c == 2 ? Ui.R_DANGER : Ui.R_ONOFF);
            if (c == 2) b.setTextColor(Ui.DANGER);
            Ui.click(b, new Runnable() {
                @Override public void run() { setWin(ci); }
            });
            winBtns.add(b);
        }
        box3(winBtns.get(0), winBtns.get(1), winBtns.get(2), lc, Ui.dp(this, 46), Ui.dp(this, 8));
        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < CarCtl.WIN_NAME.length; i++) {
            TextView t = Ui.button(this, CarCtl.WIN_NAME[i] + "：--", 11, false,
                    4, 4, Ui.R_WHITE);
            t.setClickable(false);
            t.setGravity(Gravity.CENTER);
            winState.add(t);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) p.setMarginStart(Ui.dp(this, 4));
            st.addView(t, p);
        }
        LinearLayout.LayoutParams swp = Ui.lw();
        swp.topMargin = Ui.dp(this, 8);
        lc.addView(st, swp);

        LinearLayout rc = Ui.card(this, 16);
        ScrollView rsv = new ScrollView(this);
        rsv.setFillViewport(true);
        rsv.addView(rc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rlp.setMarginStart(Ui.dp(this, 10));
        row.addView(rsv, rlp);
        rc.addView(carImage(R.drawable.car_tail, Ui.dp(this, 120)), Ui.lw());
        tailBtn = Ui.button(this, "尾门关闭状态", 15, false, 8, 0, Ui.R_BTN_ON);
        tailBtn.setTextColor(Color.WHITE);
        Ui.click(tailBtn, new Runnable() {
            @Override public void run() { setTail(); }
        });
        LinearLayout.LayoutParams op = Ui.lw();
        op.topMargin = Ui.dp(this, 14);
        op.height = Ui.dp(this, 54);
        rc.addView(tailBtn, op);
        LinearLayout.LayoutParams spn = Ui.lw();
        spn.height = 0;
        spn.weight = 1f;
        rc.addView(new View(this), spn);
        return row;
    }

    private View carImage(int res, int hdp) {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        try { iv.setImageDrawable(getDrawable(res)); } catch (Throwable t) { }
        LinearLayout.LayoutParams lp = Ui.lw();
        lp.height = hdp;
        lp.gravity = Gravity.CENTER;
        return iv;
    }

    /** 一排三颗等宽按钮塞进竖向卡片。 */
    private void box3(TextView a, TextView b, TextView c, LinearLayout host,
                      int hdp, int topdp) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] arr = {a, b, c};
        for (int i = 0; i < arr.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, hdp, 1f);
            if (i > 0) p.setMarginStart(Ui.dp(this, 6));
            r.addView(arr[i], p);
        }
        host.addView(r, withMargin(Ui.lw(), Ui.dp(this, topdp)));
    }

    /** 0=透气 1=全开 2=全关 */
    private void setWin(int i) {
        if (winBusy) { toast("车窗指令还在等回执，稍一下"); return; }
        int[] wants = {CarCtl.WIN_VENT, CarCtl.WIN_OPEN, CarCtl.WIN_CLOSE};
        int want = wants[i];
        for (int k = 0; k < winBtns.size(); k++) {
            if (k == 2) continue;
            TextView b = winBtns.get(k);
            Ui.restyle(b, this, b.getText().toString(), k == i, Ui.R_ONOFF);
        }
        if (i == 2) winBtns.get(2).setBackground(Ui.paint(this, Ui.R_DANGER, 10));
        final int idx = i;
        withSvc(new SvcBody() {
            @Override public void run(CastService svc) {
                winBusy = true;
                svc.car().setAllWindows(want, new CarCtl.ResultCallback() {
                    @Override public void accept(boolean ok, String message) {
                        winBusy = false;
                    }
                });
            }
        });
    }

    /** 尾门：一颗按钮按当前状态反向发指令。 */
    private void setTail() {
        if (tailBusy) { toast("尾门指令还在等回执（电动尾门本来就要十几秒）"); return; }
        withSvc(new SvcBody() {
            @Override public void run(CastService svc) {
                tailBusy = true;
                tailBtn.setEnabled(false);
                svc.car().setTailgate(!tailOpenState, new CarCtl.ResultCallback() {
                    @Override public void accept(boolean ok, String message) {
                        tailBusy = false;
                        tailBtn.setEnabled(true);
                    }
                });
            }
        });
    }

    /** 每次刷新把四窗状态、尾门颜色跟上真实回执。 */
    private void syncCarStates() {
        final CastService svc = CastService.inst();
        if (svc == null) return;
        if (!winBusy) {
            svc.car().readWindows(new CarCtl.IntArrayCallback() {
                @Override public void accept(int[] v) {
                    for (int i = 0; i < winState.size(); i++) {
                        TextView t = winState.get(i);
                        t.setText(CarCtl.WIN_NAME[i] + "：" +
                                CarCtl.winName(i < v.length ? v[i] : -1));
                    }
                }
            });
        }
        if (!tailBusy) {
            svc.car().readTailgate(new CarCtl.IntCallback() {
                @Override public void accept(int raw) {
                    if (raw < 0) {
                        if (!"尾门状态未知".equals(tailBtn.getText())) {
                            tailOpenState = false;
                            tailBtn.setText("尾门状态未知");
                            tailBtn.setBackground(Ui.paint(MainActivity.this, Ui.R_WHITE, 10));
                            tailBtn.setTextColor(Ui.INK_SUB);
                        }
                    } else {
                        boolean open = raw != 0;
                        if (open != tailOpenState || "状态读取中".equals(tailBtn.getText())) {
                            tailOpenState = open;
                            tailBtn.setText(open ? "尾门开启状态" : "尾门关闭状态");
                            tailBtn.setBackground(Ui.paint(MainActivity.this,
                                    open ? Ui.R_REC : Ui.R_BTN_ON, 10));
                            tailBtn.setTextColor(Color.WHITE);
                        }
                    }
                }
            });
        }
    }

    // ---------- 倒车页 ----------

    private View pageReverse() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        revCamHost = new LinearLayout(this);
        revCamHost.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f);
        row.addView(revCamHost, cp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rp.leftMargin = Ui.dp(this, 10);
        row.addView(scroll, rp);
        final LinearLayout box = Ui.card(this, 16);
        scroll.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        revOn = big("开启", Ui.R_GREEN, Color.WHITE, new Runnable() {
            @Override public void run() { setReverse(true); }
        });
        TextView off = big("关闭", Ui.R_DANGER, Ui.DANGER, new Runnable() {
            @Override public void run() { setReverse(false); }
        });
        revGear = big("档位：--", Ui.R_WHITE, new Runnable() {
            @Override public void run() { readGear(true); }
        });
        box.addView(btnRow(revOn, off, revGear), Ui.lw());

        mirNorm = big("正常位：未保存", Ui.R_WHITE, new Runnable() {
            @Override public void run() { readMirrorPos(true); }
        });
        box.addView(btnRow(mirNorm,
                big("读取", Ui.R_WHITE, new Runnable() {
                    @Override public void run() { readMirrorPos(true); }
                }),
                big("保存", Ui.R_ONOFF, new Runnable() {
                    @Override public void run() { saveMirrorPos(true); }
                })), withMargin(Ui.lw(), Ui.dp(this, 6)));

        box.addView(Ui.divider(this), withMargin(Ui.lw(), Ui.dp(this, 8)));

        mirDownB = big("下翻位：未保存", Ui.R_WHITE, new Runnable() {
            @Override public void run() { readMirrorPos(false); }
        });
        box.addView(btnRow(mirDownB,
                big("读取", Ui.R_WHITE, new Runnable() {
                    @Override public void run() { readMirrorPos(false); }
                }),
                big("保存", Ui.R_ONOFF, new Runnable() {
                    @Override public void run() { saveMirrorPos(false); }
                })), withMargin(Ui.lw(), Ui.dp(this, 6)));

        LinearLayout.LayoutParams spn = Ui.lw();
        spn.height = 0;
        spn.weight = 1f;
        box.addView(new View(this), spn);
        syncMirror();
        return row;
    }

    /** 统一尺寸的按钮。 */
    private TextView big(String name, int kind, Runnable body) {
        return big(name, kind, 0, body);
    }

    private TextView big(final String name, final int kind, final int color,
                         final Runnable body) {
        TextView b = Ui.button(this, name, 13, false, 6, 0, kind);
        if (color != 0) b.setTextColor(color);
        Ui.click(b, new Runnable() {
            @Override public void run() { body.run(); }
        });
        return b;
    }

    /** 「读取」：把总线上左右镜当前位置读回来。 */
    private void readMirrorPos(final boolean toNormal) {
        if (mirBusy) return;
        mirBusy = true;
        final TextView host = toNormal ? mirNorm : mirDownB;
        final String prev = host.getText().toString();
        host.setText((toNormal ? "正常位：" : "下翻位：") + "读取中");
        withSvc(new SvcBody() {
            @Override public void run(CastService svc) {
                svc.car().readMirrorPos(new CarCtl.MirrorCallback() {
                    @Override public void accept(int l, int r) {
                        mirBusy = false;
                        if (mirNorm == null) return;
                        if (l < 0 || r < 0) {
                            host.setText(prev);
                            slog("镜位读取失败：总线上 201/218 没回执" +
                                    (r < 0 && l >= 0 ? "（左=" + l + " 右无）" : ""));
                            return;
                        }
                        if (toNormal) { mirNormReadL = l; mirNormReadR = r; }
                        else { mirDownReadL = l; mirDownReadR = r; }
                        if (toNormal) mirNorm.setText("正常位：左" + l + " 右" + r);
                        else mirDownB.setText("下翻位：左" + l + " 右" + r);
                    }
                });
            }
        });
    }

    /** 「保存」：把刚读到的那一对位置钉成正常位 / 下翻位。 */
    private void saveMirrorPos(boolean toNormal) {
        int l = toNormal ? mirNormReadL : mirDownReadL;
        int r = toNormal ? mirNormReadR : mirDownReadR;
        if (l < 0 || r < 0) {
            slog((toNormal ? "正常位" : "下翻位") + "还没读到位置，保存不了：先按「读取」");
            toast("先按读取");
            return;
        }
        if (toNormal) cfg.setMirrorNormal(l, r); else cfg.setMirrorDown(l, r);
        slog((toNormal ? "正常位已保存：左=" : "下翻位已保存：左=") + l +
                " 右=" + r + (toNormal ? "（退 R 写回这里）" : "（挂 R 推到这里）"));
        if (toNormal) mirNorm.setText("正常位：已存 左" + l + " 右" + r);
        else mirDownB.setText("下翻位：已存 左" + l + " 右" + r);
        if (!cfg.mirrorReady() && cfg.mirrorNormalReady())
            slog("还差下翻位：把镜手动调到下翻角度→读取→保存");
    }

    private final HashMap<String, CamView> revTiles = new HashMap<>();
    private final HashMap<String, Integer> revIdx = new HashMap<>();

    private void buildRevCams() {
        if (revCamHost == null) return;
        revCamHost.removeAllViews();
        revTiles.clear();
        revIdx.clear();
        revTile(revCamHost, "左盲区", CamCtl.ID_LEFT, 0, 1f, 0);
        revTile(revCamHost, "360全景", CamCtl.ID_FRONT, 1, 1.5f, Ui.dp(this, 8));
        revTile(revCamHost, "右盲区", CamCtl.ID_RIGHT, 2, 1f, Ui.dp(this, 8));
    }

    private void revTile(final LinearLayout parent, final String name, final String id,
                         final int idx, float w, int ml) {
        final CamView v = new CamView(this, name, idx == revSel);
        v.setClickable(true);
        Ui.click(v, new Runnable() {
            @Override public void run() {
                revSel = idx;
                for (Map.Entry<String, CamView> e : revTiles.entrySet()) {
                    Integer ri = revIdx.get(e.getKey());
                    e.getValue().selected(ri != null && ri == idx);
                }
                slog("切换倒车视角：" + name);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, w);
        lp.setMarginStart(ml);
        parent.addView(v, lp);
        revTiles.put(id, v);
        revIdx.put(id, idx);
        withSvc(new SvcBody() {
            @Override public void run(CastService svc) {
                svc.camsAttach(v, id, name);
            }
        });
    }

    /** 两组镜位按钮的文字。 */
    private void syncMirror() {
        if (mirNorm == null) return;
        if (!mirBusy) {
            int nl = cfg.mirrorNormalL();
            String normStatus;
            if (nl >= 0) normStatus = "已存 左" + nl + " 右" + cfg.mirrorNormalR();
            else if (mirNormReadL >= 0) normStatus = "读到 左" + mirNormReadL + " 右" + mirNormReadR;
            else normStatus = "未保存";
            mirNorm.setText("正常位：" + normStatus);

            int dl = cfg.mirrorDownL();
            String downStatus;
            if (dl >= 0) downStatus = "已存 左" + dl + " 右" + cfg.mirrorDownR();
            else if (mirDownReadL >= 0) downStatus = "读到 左" + mirDownReadL + " 右" + mirDownReadR;
            else downStatus = "未保存";
            mirDownB.setText("下翻位：" + downStatus);
        }
        Ui.restyle(revOn, this, "开启", cfg.mirrorDip(), Ui.R_ONOFF);
        if (cfg.mirrorDip()) revOn.setTextColor(Color.WHITE);
    }

    private void setReverse(boolean on) {
        if (on && !cfg.mirrorReady()) {
            toast("先把下翻位读取并保存");
            slog("自动下翻没开：下翻位还没保存，不敢乱写镜子");
            return;
        }
        if (on && !cfg.mirrorNormalReady())
            slog("提醒：正常位还没保存，退 R 时镜位不会自动写回");
        cfg.setMirrorDip(on);
        slog("自动下翻功能 -> " + (on ? "开启（挂 R 写下翻位，退 R 写回正常位）" : "关闭"));
        syncMirror();
    }

    // ---------- 记录仪页 ----------

    private View pageDvr() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);

        dvrCamHost = new LinearLayout(this);
        dvrCamHost.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        row.addView(dvrCamHost, gp);

        ScrollView card = new ScrollView(this);
        card.setFillViewport(true);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        card.setVisibility(View.GONE);
        row.addView(card, cp);
        dvrPanel = card;
        final LinearLayout box = Ui.card(this, 16);
        card.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        recChip = big("没在录像", Ui.R_WHITE, new Runnable() {
            @Override public void run() { }
        });
        recPause = big("开始录像", Ui.R_ONOFF, new Runnable() {
            @Override public void run() {
                withSvc(new SvcBody() {
                    @Override public void run(CastService svc) {
                        if (svc.dvrRecording()) {
                            svc.dvrStop();
                            slog("正在停录收尾…");
                        } else {
                            svc.dvrStart();
                            slog("正在起四宫格录像（切页也继续录）…");
                        }
                    }
                });
                syncDvr();
            }
        });
        recSeg = big("", Ui.R_WHITE, new Runnable() {
            @Override public void run() { }
        });

        // 底部操作栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, Ui.dp(this, 8), 0, 0);
        LinearLayout.LayoutParams dvp = Ui.lw();
        dvp.height = Ui.dp(this, 44);
        row.addView(bar, dvp);
        bar.addView(recChip, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.6f);
        blp.setMarginStart(Ui.dp(this, 8));
        bar.addView(recPause, blp);
        LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f);
        rlp2.setMarginStart(Ui.dp(this, 8));
        bar.addView(recSeg, rlp2);
        btnDvrSet = big("设置", Ui.R_ONOFF, new Runnable() {
            @Override public void run() { dvrSettings(false); }
        });
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        slp2.setMarginStart(Ui.dp(this, 8));
        bar.addView(btnDvrSet, slp2);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        plp.setMarginStart(Ui.dp(this, 8));
        bar.addView(big("回放", Ui.R_WHITE, new Runnable() {
            @Override public void run() {
                startActivity(new Intent(MainActivity.this, PlayerActivity.class));
            }
        }), plp);

        // 循环分段
        loopBtns.clear();
        for (int li = 0; li < CamCtl.LOOP_MIN.length; li++) {
            final int m = CamCtl.LOOP_MIN[li];
            TextView b = big(m + "分钟", Ui.R_ONOFF, new Runnable() {
                @Override public void run() {
                    cfg.setLoopMin(m);
                    withSvc(new SvcBody() {
                        @Override public void run(CastService svc) {
                            svc.cams().loopMinutes = m;
                        }
                    });
                    slog("循环分段 -> " + m + " 分钟（录满自动删最旧一段）");
                    syncDvr();
                }
            });
            loopBtns.add(b);
        }
        box.addView(btnRowN(4, loopBtns.get(0), loopBtns.get(1), loopBtns.get(2), loopBtns.get(3)),
                withMargin(Ui.lw(), Ui.dp(this, 6)));

        // 三个开关
        btnSound = big("声音", Ui.R_ONOFF, new Runnable() {
            @Override public void run() {
                boolean on = !cfg.dvrSound();
                if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED) {
                    askedCam = true;
                    slog("录音权限还没给，弹系统授权框");
                    try {
                        requestPermissions(new String[]{
                                Manifest.permission.RECORD_AUDIO}, REQ_CAM);
                    } catch (Throwable t) {
                        slog("弹不出授权框：" + t.getClass().getSimpleName() + "（先只录画面）");
                    }
                } else {
                    cfg.setDvrSound(on);
                    slog(on ? "录像带声音（下一段起生效）" : "录像不带声音（下一段起生效）");
                }
                syncDvr();
            }
        });
        btnWm = big("水印", Ui.R_ONOFF, new Runnable() {
            @Override public void run() {
                boolean on = !cfg.dvrWatermark();
                cfg.setDvrWatermark(on);
                slog(on ? "水印已开：时间/车速/档位烧进画面（下一段起生效）"
                         : "水印已关（下一段起生效）");
                syncDvr();
            }
        });
        btnGearRec = big("挡位自动录", Ui.R_ONOFF, new Runnable() {
            @Override public void run() {
                boolean on = !cfg.dvrAutoGear();
                cfg.setDvrAutoGear(on);
                slog(on ? "挂 R/D 自动开录、挂 P 收尾（换挡沿触发）" : "挡位自动录已关");
                syncDvr();
            }
        });
        box.addView(btnRow(btnSound, btnWm, btnGearRec),
                withMargin(Ui.lw(), Ui.dp(this, 6)));

        // 画质档 + 紧急锁定
        qBtns.clear();
        String[] qNames = {"低画质", "标准", "高画质"};
        for (int qi = 0; qi < qNames.length; qi++) {
            final int qIdx = qi;
            TextView b = big(qNames[qi], Ui.R_ONOFF, new Runnable() {
                @Override public void run() {
                    cfg.setDvrQuality(qIdx);
                    slog("画质档 -> " + qNames[qIdx] + "（下一段生效）");
                    syncDvr();
                }
            });
            qBtns.add(b);
        }
        btnEmg = big("紧急锁定", Ui.R_ONOFF, new Runnable() {
            @Override public void run() {
                boolean on = !cfg.dvrEmergency();
                if (on) {
                    CastService s0 = svc0();
                    String err = s0 != null ? s0.dvrEmergency(true) : null;
                    if (err != null) {
                        slog("紧急锁定开不了：" + err);
                        syncDvr();
                        return;
                    }
                    cfg.setDvrEmergency(true);
                    slog("紧急锁定：撞击/急刹自动把这一段封口存进 锁定 目录");
                } else {
                    cfg.setDvrEmergency(false);
                    withSvc(new SvcBody() {
                        @Override public void run(CastService svc) {
                            svc.dvrEmergency(false);
                        }
                    });
                    slog("紧急锁定已关");
                }
                syncDvr();
            }
        });
        box.addView(btnRowN(4, qBtns.get(0), qBtns.get(1), qBtns.get(2), btnEmg),
                withMargin(Ui.lw(), Ui.dp(this, 6)));

        // 画面调节
        picBtns.clear();
        final String[] picNames = {"标准", "明亮", "鲜艳", "柔和"};
        for (int pi = 0; pi < picNames.length; pi++) {
            final int pIdx = pi;
            TextView b = big(picNames[pi], Ui.R_ONOFF, new Runnable() {
                @Override public void run() {
                    cfg.setDvrPicture(pIdx);
                    CastService s0 = svc0();
                    String msg = s0 != null ? s0.cams().applyPicture() : null;
                    slog(msg != null ? msg : ("画面调节 -> " + picNames[pIdx] + "（下一段生效）"));
                    syncDvr();
                }
            });
            picBtns.add(b);
        }
        box.addView(btnRowN(4, picBtns.get(0), picBtns.get(1), picBtns.get(2), picBtns.get(3)),
                withMargin(Ui.lw(), Ui.dp(this, 6)));

        // 开机自动录 / 音效 / 存储上限
        btnBootRec = big("开机自动录", Ui.R_ONOFF, new Runnable() {
            @Override public void run() {
                boolean on = !cfg.dvrAutoBoot();
                cfg.setDvrAutoBoot(on);
                slog(on ? "开机后服务一起来就自动开录（第一次仍需给过摄像头授权）"
                         : "开机自动录已关");
                syncDvr();
            }
        });
        btnTone = big("录像音效", Ui.R_ONOFF, new Runnable() {
            @Override public void run() {
                boolean on = !cfg.dvrTone();
                cfg.setDvrTone(on);
                slog(on ? "开录/停录各响一声提示音" : "提示音已关");
                syncDvr();
            }
        });
        btnCap = big("存储上限", Ui.R_ONOFF, new Runnable() {
            @Override public void run() {
                int curCap = cfg.dvrCapMB();
                int nxt = curCap == 0 ? 4096 : (curCap == 4096 ? 8192 : 0);
                cfg.setDvrCapMB(nxt);
                slog(nxt == 0 ? "存储上限：不限（只按循环时长删）"
                               : "存储上限 " + (nxt / 1024) + "GB，超了自动删最旧段（锁定不动）");
                syncDvr();
            }
        });
        box.addView(btnRow(btnBootRec, btnTone, btnCap),
                withMargin(Ui.lw(), Ui.dp(this, 6)));

        // EVCam 移植：息屏锁车录制
        btnScrOff = big("息屏锁车录制", Ui.R_ONOFF, new Runnable() {
            @Override public void run() {
                boolean on = !cfg.dvrScreenOff();
                cfg.setDvrScreenOff(on);
                slog(on ? "息屏录制开：熄屏 10 秒自动开录、亮屏 10 秒自动收尾"
                         : "息屏录制已关");
                syncDvr();
            }
        });
        // EVCam 移植：录制状态悬浮钮
        btnFloat = big("录制悬浮钮", Ui.R_ONOFF, new Runnable() {
            @Override public void run() {
                boolean on = !cfg.dvrFloat();
                cfg.setDvrFloat(on);
                CastService svc = svc0();
                if (on) {
                    if (!RecFloat.canDraw(MainActivity.this)) {
                        slog("悬浮钮开不了：没给「显示在其他应用上层」权限，先开通");
                        try { startActivity(new Intent(
                                "android.settings.action.MANAGE_OVERLAY_PERMISSION",
                                android.net.Uri.parse("package:" + getPackageName()))); }
                        catch (Throwable t) { slog("跳不过权限页：" + t.getMessage()); }
                    } else {
                        withSvc(new SvcBody() {
                            @Override public void run(CastService s) { RecFloat.show(s); }
                        });
                        slog("录制悬浮钮已开（桌面左上角浮动绿/红圆点）");
                    }
                } else {
                    withSvc(new SvcBody() {
                        @Override public void run(CastService s) { RecFloat.hide(s); }
                    });
                    slog("录制悬浮钮已关");
                }
                syncDvr();
            }
        });
        // EVCam 移植：参录摄像头选择（bit0=前 bit1=后 bit2=左 bit3=右）
        for (int mi = 0; mi < CamCtl.TILES.length; mi++) {
            final int ti = mi;
            final String nm = CamCtl.TILES[mi][1];
            TextView mb = big(nm, Ui.R_ONOFF, new Runnable() {
                @Override public void run() {
                    int cur = cfg.dvrMask();
                    cfg.setDvrMask(cur ^ (1 << ti));
                    withSvc(new SvcBody() {
                        @Override public void run(CastService svc) {
                            String res = svc.cams().applyMask(cfg.dvrMask());
                            slog(res);
                        }
                    });
                    syncDvr();
                }
            });
            maskBtns.add(mb);
        }
        box.addView(btnRow(btnScrOff, btnFloat),
                withMargin(Ui.lw(), Ui.dp(this, 6)));
        box.addView(btnRowN(4, maskBtns.get(0), maskBtns.get(1), maskBtns.get(2), maskBtns.get(3)),
                withMargin(Ui.lw(), Ui.dp(this, 6)));
        // EVCam 移植：照片存储上限
        btnPhotoCap = big("照片上限", Ui.R_ONOFF, new Runnable() {
            @Override public void run() {
                int cur = cfg.dvrPhotoCapMB();
                int nxt = cur == 0 ? 1024 : (cur == 1024 ? 2048 : 0);
                cfg.setDvrPhotoCapMB(nxt);
                slog(nxt == 0 ? "照片上限：不限"
                               : "照片上限 " + (nxt / 1024) + "GB，超了自动删最旧张");
                syncDvr();
            }
        });
        box.addView(btnRow(btnPhotoCap, big("回放相册", Ui.R_WHITE, new Runnable() {
            @Override public void run() {
                startActivity(new Intent(MainActivity.this, PlayerActivity.class));
            }
        })),
                withMargin(Ui.lw(), Ui.dp(this, 6)));

        // 存储位置 / 拍照 / 四宫格 / 锁定 / 返回
        storLine = big("存储位置", Ui.R_WHITE, new Runnable() {
            @Override public void run() {
                storLine.setText(Storage.describe(MainActivity.this));
            }
        });
        box.addView(btnRow(storLine,
                big("拍照这一路", Ui.R_WHITE, new Runnable() {
                    @Override public void run() {
                        final CamView b2 = dvrTiles.get(dvrSelected);
                        if (b2 == null) return;
                        withSvc(new SvcBody() {
                            @Override public void run(CastService svc) {
                                svc.dvrSnapshot(b2, new CamCtl.StrCb() {
                                    @Override public void accept(String p) {
                                        slog(p == null ? "这路还没出画，拍不了"
                                                        : "拍照已存：" + p);
                                    }
                                });
                            }
                        });
                    }
                }),
                big("四宫格一张图", Ui.R_WHITE, new Runnable() {
                    @Override public void run() {
                        withSvc(new SvcBody() {
                            @Override public void run(CastService svc) {
                                svc.dvrQuad(new CarCtl.LogCallback() {
                                    @Override public void log(String m) { slog(m); }
                                });
                            }
                        });
                    }
                })),
                withMargin(Ui.lw(), Ui.dp(this, 6)));
        box.addView(btnRow(
                big("锁定视频", Ui.R_WHITE, new Runnable() {
                    @Override public void run() {
                        withSvc(new SvcBody() {
                            @Override public void run(CastService svc) {
                                svc.dvrLock(new CarCtl.LogCallback() {
                                    @Override public void log(String m) { slog(m); }
                                });
                            }
                        });
                    }
                }),
                big("返回预览", Ui.R_ONOFF, new Runnable() {
                    @Override public void run() { dvrSettings(true); }
                })),
                withMargin(Ui.lw(), Ui.dp(this, 6)));
        LinearLayout.LayoutParams dspn = Ui.lw();
        dspn.height = 0;
        dspn.weight = 1f;
        box.addView(new View(this), dspn);
        if (btnDvrSet != null) btnDvrSet.setText("设置");
        return row;
    }

    /** 预览 ↔ 设置整页互换。 */
    private void dvrSettings(boolean toPreview) {
        dvrSetOn = !toPreview;
        if (dvrCamHost != null) dvrCamHost.setVisibility(toPreview ? View.VISIBLE : View.GONE);
        if (dvrPanel != null) dvrPanel.setVisibility(toPreview ? View.GONE : View.VISIBLE);
        if (btnDvrSet != null)
            Ui.restyle(btnDvrSet, this, toPreview ? "设置" : "收起", dvrSetOn, Ui.R_ONOFF);
        if (toPreview) {
            CastService s = svc0();
            if (s != null) s.cams().restart();
        }
    }

    private final HashMap<String, CamView> dvrTiles = new HashMap<>();
    private final String[][] dvrOrder = {
            {CamCtl.ID_FRONT, "前视"}, {CamCtl.ID_BACK, "后视"},
            {CamCtl.ID_LEFT, "左视"}, {CamCtl.ID_RIGHT, "右视"}};

    private void buildDvrCams() {
        if (dvrCamHost == null) return;
        dvrCamHost.removeAllViews();
        dvrTiles.clear();
        for (int r = 0; r <= 1; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c <= 1; c++) dvrCam(line, dvrOrder[r * 2 + c]);
            LinearLayout.LayoutParams lp = Ui.lw();
            lp.height = 0;
            lp.weight = 1f;
            if (r == 1) lp.topMargin = Ui.dp(this, 8);
            dvrCamHost.addView(line, lp);
        }
    }

    private void dvrCam(final LinearLayout parent, final String[] tile) {
        final CamView v = new CamView(this, tile[1], tile[0].equals(dvrSelected));
        v.setClickable(true);
        Ui.click(v, new Runnable() {
            @Override public void run() {
                dvrSelected = tile[0];
                for (Map.Entry<String, CamView> e : dvrTiles.entrySet()) {
                    e.getValue().selected(e.getKey().equals(dvrSelected));
                }
                slog("记录仪选中视角：" + tile[1] + "（cameraId " + tile[0] + "）");
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (parent.getChildCount() > 0) lp.setMarginStart(Ui.dp(this, 8));
        parent.addView(v, lp);
        dvrTiles.put(tile[0], v);
        withSvc(new SvcBody() {
            @Override public void run(CastService svc) {
                svc.camsAttach(v, tile[0], tile[1]);
            }
        });
    }

    /** 记录仪页的状态全部来自我们自己的录像器。 */
    private void syncDvr() {
        CastService svc = CastService.inst();
        if (svc == null) return;
        boolean on = svc.dvrRecording();
        if (on) {
            recChip.setText("四宫格录像中");
            recChip.setBackground(Ui.paint(this, Ui.R_REC, 10));
            recChip.setTextColor(Color.WHITE);
            recPause.setText("停止录像");
        } else {
            recChip.setText("没在录像");
            recChip.setBackground(Ui.paint(this, Ui.R_WHITE, 10));
            recChip.setTextColor(Ui.INK_SUB);
            recPause.setText("开始录像");
        }
        recSeg.setText(on ? svc.dvrSegment() : "");
        recSeg.setVisibility(on ? View.VISIBLE : View.GONE);
        storLine.setText(Storage.describe(this));
        for (int i = 0; i < loopBtns.size(); i++)
            Ui.restyle(loopBtns.get(i), this, loopBtns.get(i).getText().toString(),
                    CamCtl.LOOP_MIN[i] == cfg.loopMin(), Ui.R_ONOFF);
        Ui.restyle(btnSound, this, "声音",
                cfg.dvrSound() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED, Ui.R_ONOFF);
        Ui.restyle(btnWm, this, "水印", cfg.dvrWatermark(), Ui.R_ONOFF);
        Ui.restyle(btnGearRec, this, "挡位自动录", cfg.dvrAutoGear(), Ui.R_ONOFF);
        for (int i = 0; i < qBtns.size(); i++)
            Ui.restyle(qBtns.get(i), this, qBtns.get(i).getText().toString(),
                    cfg.dvrQuality() == i, Ui.R_ONOFF);
        Ui.restyle(btnEmg, this, "紧急锁定", cfg.dvrEmergency(), Ui.R_ONOFF);
        for (int i = 0; i < picBtns.size(); i++)
            Ui.restyle(picBtns.get(i), this, picBtns.get(i).getText().toString(),
                    cfg.dvrPicture() == i, Ui.R_ONOFF);
        Ui.restyle(btnBootRec, this, "开机自动录", cfg.dvrAutoBoot(), Ui.R_ONOFF);
        Ui.restyle(btnTone, this, "录像音效", cfg.dvrTone(), Ui.R_ONOFF);
        int cap = cfg.dvrCapMB();
        Ui.restyle(btnCap, this, cap == 0 ? "存储上限" : "上限" + (cap / 1024) + "G",
                cap > 0, Ui.R_ONOFF);
        Ui.restyle(btnScrOff, this, "息屏锁车录制", cfg.dvrScreenOff(), Ui.R_ONOFF);
        Ui.restyle(btnFloat, this, "录制悬浮钮", cfg.dvrFloat(), Ui.R_ONOFF);
        for (int i = 0; i < maskBtns.size(); i++)
            Ui.restyle(maskBtns.get(i), this, maskBtns.get(i).getText().toString(),
                    (cfg.dvrMask() & (1 << i)) != 0, Ui.R_ONOFF);
        int pcap = cfg.dvrPhotoCapMB();
        Ui.restyle(btnPhotoCap, this, pcap == 0 ? "照片上限" : "照片上限" + (pcap / 1024) + "G",
                pcap > 0, Ui.R_ONOFF);
    }

    // ---------- 公共小件 ----------

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36));
        lp.rightMargin = Ui.dp(this, 8);
        return lp;
    }

    private TextView sBtn(String name, final Runnable body) {
        TextView b = Ui.button(this, name, 13, false);
        Ui.click(b, new Runnable() {
            @Override public void run() { body.run(); }
        });
        return b;
    }

    private LinearLayout flowRow(View... vs) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        for (View v : vs) r.addView(v, gap());
        return r;
    }

    /** 投屏页按钮排：固定一排三个等宽。 */
    private LinearLayout btnRow(View... vs) {
        return btnRowN(3, vs);
    }

    /** 一行 N 个等宽。 */
    private LinearLayout btnRowN(int n, View... vs) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        int dp8 = Ui.dp(this, 8);
        for (int i = 0; i < n; i++) {
            View cell = i < vs.length ? vs[i] : new View(this);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    0, Ui.dp(this, 36), 1f);
            if (i > 0) p.setMarginStart(dp8);
            r.addView(cell, p);
        }
        return r;
    }

    private TextView sBtnRestart() {
        TextView b = Ui.button(this, "重启服务", 13, false, 4, 7, Ui.R_DANGER);
        b.setTextColor(Ui.DANGER);
        Ui.click(b, new Runnable() {
            @Override public void run() { restartService(); }
        });
        return b;
    }

    /**
     * 探测原厂投屏服务（v4.7.5 版：两条通道依次试）。
     */
    private TextView sBtnProj() {
        final TextView b = Ui.button(this, "探测原厂投屏", 13, false, 4, 7, Ui.R_ONOFF);
        Ui.click(b, new Runnable() {
            @Override public void run() {
                slog("探测原厂投屏：两条通道依次试（ServiceManager -> bindService）…");
                new Thread(new Runnable() {
                    @Override public void run() {
                        final android.os.IBinder svc = ProjCtl.getService();
                        handler.post(new Runnable() {
                            @Override public void run() {
                                if (svc != null) {
                                    slog("原厂投屏服务拿到了（desaysv_project_service，可试全屏通道）");
                                } else {
                                    String err = ProjCtl.bind(MainActivity.this);
                                    if (err == null) {
                                        slog("ServiceManager 不给，但 bindService 连上了（原厂投屏可以试）");
                                    } else {
                                        slog("拿不到原厂投屏服务。ServiceManager 那条：没这个服务名或被 SELinux 拦；bindService 那条：" + err + "。投屏照旧走我们自己的通道，这个只是探测。");
                                    }
                                }
                            }
                        });
                    }
                }).start();
            }
        });
        return b;
    }

    private TextView sBtnCluster() {
        return sBtn("仪表悬浮音乐层", new Runnable() {
            @Override public void run() {
                if (ClusterOverlay.alive()) {
                    ClusterOverlay.stop(MainActivity.this);
                    slog("仪表悬浮音乐层已关闭");
                } else if (!ClusterOverlay.canDraw(MainActivity.this)) {
                    slog("没有悬浮窗权限，自动跳转开通");
                    toast("请允许「显示在其他应用上层」");
                    try {
                        startActivity(new Intent(
                                "android.settings.action.MANAGE_OVERLAY_PERMISSION",
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Throwable t) {
                        toast("这台车没有悬浮窗设置页，用 adb：" +
                                "appops set com.ahui.clustercast SYSTEM_ALERT_WINDOW allow");
                    }
                } else {
                    ClusterOverlay.start(MainActivity.this);
                    handler.postDelayed(new Runnable() {
                        @Override public void run() {
                            slog(ClusterOverlay.alive() ?
                                    "悬浮层已挂到仪表屏最高层，盖没盖住看屏幕" :
                                    "悬浮层启动失败，看上面几行的原因");
                        }
                    }, 1500);
                }
            }
        });
    }

    /** 总线抓包。 */
    private TextView sBtnSniff() {
        final TextView b = Ui.button(this, "总线抓包", 13, false, 4, 7, Ui.R_ONOFF);
        CastService inst0 = CastService.inst();
        Ui.restyle(b, this, "总线抓包",
                inst0 != null && inst0.sniffing(), Ui.R_ONOFF);
        Ui.click(b, new Runnable() {
            @Override public void run() {
                CastService svc = CastService.inst();
                if (svc == null) { toast("后台服务还在启动，稍等一下"); return; }
                if (svc.sniffing()) {
                    svc.stopSniff();
                } else {
                    String err = svc.startSniff();
                    if (err != null) slog("抓包开不了：" + err);
                }
                CastService svc2 = CastService.inst();
                Ui.restyle(b, MainActivity.this, "总线抓包",
                        svc2 != null && svc2.sniffing(), Ui.R_ONOFF);
            }
        });
        return b;
    }

    private LinearLayout.LayoutParams withMargin(LinearLayout.LayoutParams lp, int px) {
        lp.topMargin = px;
        return lp;
    }

    private void openPicker() {
        startActivity(new Intent(this, AppPicker.class));
    }

    private void pickTheme(int t) {
        cfg.setCastTheme(t);
        syncToggles();
    }

    private void syncToggles() {
        Ui.restyle(btnNavi, this, "导航模式",
                cfg.castTheme() == Vd.THEME_NAVI, Ui.R_ONOFF);
        Ui.restyle(btnSimple, this, "极简模式",
                cfg.castTheme() == Vd.THEME_SIMPLE, Ui.R_ONOFF);
        Ui.restyle(btnFollow, this, "跟随前台", cfg.followTop(), Ui.R_ONOFF);
        Ui.restyle(btnGuard, this, "顶掉管理员弹窗", cfg.guard(), Ui.R_ONOFF);
        Ui.restyle(btnFill, this, "整屏填充", cfg.castFill(), Ui.R_ONOFF);
        Ui.restyle(btnDock, this, "沉浸dock", cfg.dockImmersive(), Ui.R_ONOFF);
        Ui.restyle(btnTop, this, "强制顶层", cfg.forceTop(), Ui.R_ONOFF);
        Ui.restyle(btnMirror, this, "整屏镜像", cfg.mirrorMode(), Ui.R_ONOFF);
    }

    // ---------- 状态刷新 ----------

    private void refresh() {
        CastService svc = CastService.inst();
        if (svc == null) {
            busChip.setText("服务未运行");
            busChip.setTextColor(Ui.DANGER);
        } else {
            Vd vd = Vd.inst();
            boolean vdOk = vd != null && vd.ok();
            busChip.setText(vdOk ? "总线已连" : "总线未连");
            busChip.setTextColor(vdOk ? Ui.GREEN : Ui.INK_SUB);
        }
        carChip.setText(Caster.displayAlive(this, Caster.CLUSTER) ? "仪表屏已亮" : "仪表屏未亮");
        syncToggles();
        if (cur == 2 && tailBtn != null) syncCarStates();
        if (recChip != null) syncDvr();
        if (cur == 3 && svc != null) { syncMirror(); readGear(false); }
    }

    /** 只在倒车页、且上一次读已回来时才读。 */
    private void readGear(final boolean manual) {
        if (gearBusy || revGear == null) return;
        gearBusy = true;
        if (manual) revGear.setText("档位：读取中");
        CastService svc = CastService.inst();
        if (svc == null) { gearBusy = false; return; }
        svc.gearNow(new CarCtl.IntCallback() {
            @Override public void accept(int g) {
                gearBusy = false;
                if (revGear != null)
                    revGear.setText("档位：" + CarCtl.gearName(g));
            }
        });
    }

    /** 真重启。 */
    private void restartService() {
        try { stopService(new Intent(this, CastService.class)); } catch (Throwable t) { }
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                CastService.start(MainActivity.this);
                handler.postDelayed(attach, 800);
            }
        }, 400);
        slog("手动重启服务");
        toast("服务已重启");
    }

    /** 打开页面自动检查缺什么权限。 */
    private void autoPermCheck() {
        if (askedPerm) return;
        if (!TopApp.granted(this)) {
            askedPerm = true;
            slog("检测到缺少使用情况权限，自动跳转授权");
            if (!TopApp.request(this)) toast(USAGE_ADB_HINT);
            return;
        }
        if (!MusicListener.ready()) {
            askedPerm = true;
            slog("检测到缺少通知使用权，自动跳转授权");
            if (!openNotifSettings()) toast(NOTIF_ADB_HINT);
        }
    }

    private boolean openNotifSettings() {
        try {
            startActivity(new Intent(
                    "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            return true;
        } catch (Throwable t) { return false; }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---------- 常量 ----------

    public static final int REQ_CAM = 41;

    /** 这台车机没有使用情况授权页面，只能在电脑上用 adb 授权。 */
    public static final String USAGE_ADB_HINT =
            "这台车机没有使用情况授权页面。请在电脑上执行：" +
                    "adb shell appops set com.ahui.clustercast GET_USAGE_STATS allow";

    public static final String NOTIF_ADB_HINT =
            "这台车机没有通知使用权设置页。请在电脑上执行：" +
                    "adb shell cmd notification allow_listener com.ahui.clustercast/.MusicListener";
}
