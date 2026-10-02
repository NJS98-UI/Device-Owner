package com.ahui.clustercast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements CastService.LogSink {

    /** 这台 ROM 没有「使用情况访问权限」设置页，只能在电脑上用 adb 授权。 */
    private static final String USAGE_ADB_HINT =
            "这台车机没有使用情况授权页面。请在电脑上执行："
                    + "adb shell appops set com.ahui.clustercast GET_USAGE_STATS allow";

    /** 通知使用权是极简页读歌名的唯一依赖；设置页缺失时用 adb 授权，重启不失效。 */
    private static final String NOTIF_ADB_HINT =
            "这台车机没有通知使用权设置页。请在电脑上执行："
                    + "adb shell cmd notification allow_listener com.ahui.clustercast/.MusicListener";

    private final Handler handler = new Handler();
    private TextView status;
    private TextView log;
    private Cfg cfg;

    /** 静态嵌套类：本项目里捕获了 this 的匿名内部类会让 d8 崩掉。 */
    private static class Refresh implements Runnable {
        final WeakReference<MainActivity> ref;
        Refresh(MainActivity a) { ref = new WeakReference<>(a); }
        @Override public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            a.refresh();
            a.handler.postDelayed(this, 1500);
        }
    }

    private Refresh refreshLoop;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new Cfg(this);
        setContentView(build());
        CastService.start(this);
        refreshLoop = new Refresh(this);
        handler.postDelayed(refreshLoop, 300);
    }

    @Override protected void onResume() {
        super.onResume();
        // 服务是本页拉起的，onCreate 那一刻还没起来，晚一点再挂日志回调
        handler.postDelayed(attachSink, 600);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(attachSink);
        CastService svc = CastService.inst();
        if (svc != null) svc.setSink(null);
        super.onPause();
    }

    private final Runnable attachSink = new Attach(this);

    /** 静态嵌套类：捕获 this 的匿名内部类会让本项目的 d8 崩掉。 */
    private static class Attach implements Runnable {
        final WeakReference<MainActivity> ref;
        Attach(MainActivity a) { ref = new WeakReference<>(a); }
        @Override public void run() {
            MainActivity a = ref.get();
            if (a == null) return;
            CastService svc = CastService.inst();
            if (svc == null) { CastService.start(a); return; }
            svc.setSink(a);
            a.refresh();
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refreshLoop);
        CastService svc = CastService.inst();
        if (svc != null) svc.setSink(null);
        super.onDestroy();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));

        status = new TextView(this);
        status.setTextSize(14);
        root.addView(status, wrap());

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(act("选择投屏应用", v -> pickApp()));
        r1.addView(act("立即投屏", v -> withSvc(CastService::castNow)));
        r1.addView(act("退出投屏", v -> withSvc(CastService::exitNow)));
        root.addView(r1, wrap());

        CheckBox follow = new CheckBox(this);
        follow.setText("三指左滑自动投「当前前台应用」（需使用情况访问权限）");
        follow.setTextSize(13);
        follow.setChecked(cfg.followTop());
        follow.setOnCheckedChangeListener((btn, on) -> {
            cfg.setFollowTop(on);
            if (on && !TopApp.granted(this)) toast(USAGE_ADB_HINT);
        });
        root.addView(follow, wrap());

        CheckBox simple = new CheckBox(this);
        simple.setText("极简模式：三指左滑先把仪表切到原车「极简模式」，再放我们的播放页（推荐）");
        simple.setTextSize(13);
        simple.setChecked(cfg.simple());
        simple.setOnCheckedChangeListener((btn, on) -> cfg.setSimple(on));
        root.addView(simple, wrap());

        CheckBox card = new CheckBox(this);
        card.setText("对接桌面音乐卡片：把自装音乐的歌名/歌手推上车身总线，原车卡片也能显示");
        card.setTextSize(13);
        card.setChecked(cfg.cardMirror());
        card.setOnCheckedChangeListener((btn, on) -> cfg.setCardMirror(on));
        root.addView(card, wrap());

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.addView(act("使用情况授权", v -> {
            if (!TopApp.request(this)) toast(USAGE_ADB_HINT);
        }));
        r2.addView(act("通知使用权", v -> {
            if (!openNotifSettings()) toast(NOTIF_ADB_HINT);
        }));
        r2.addView(act("重启后台服务", v -> CastService.start(this)));
        root.addView(r2, wrap());

        TextView cap = new TextView(this);
        cap.setTextSize(14);
        cap.setText("日志");
        root.addView(cap, wrap());

        log = new TextView(this);
        log.setTextSize(12);
        log.setBackgroundColor(0x11000000);
        log.setPadding(dp(8), dp(8), dp(8), dp(8));
        log.setMinHeight(dp(220));
        ScrollView sc = new ScrollView(this);
        sc.addView(log);
        LinearLayout.LayoutParams slp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = dp(6);
        root.addView(sc, slp);
        return root;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private Button act(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void refresh() {
        CastService svc = CastService.inst();
        StringBuilder sb = new StringBuilder();
        sb.append(svc == null ? "后台服务：未运行（点「重启后台服务」）" : "后台服务：运行中");
        sb.append("  ·  仪表屏：").append(Caster.displayAlive(this, Caster.CLUSTER) ? "已点亮" : "未点亮");
        sb.append("  ·  模式：").append(cfg.simple() ? "极简播放页" : "整屏投应用");
        sb.append("  ·  仪表模式：").append(svc == null ? "未连" : Vd.themeName(svc.mThemeSeen));
        sb.append("  ·  通知使用权：").append(MusicListener.ready() ? "已授予" : "未授予");
        if (!cfg.simple()) {
            String label = cfg.label() != null ? cfg.label() : cfg.pkg();
            sb.append("  ·  目标：").append(label == null ? "未选择" : label);
        }
        if (cfg.followTop()) sb.append("  ·  跟随前台：").append(TopApp.granted(this) ? "已授权" : "缺授权");
        status.setText(sb);
        status.setTextColor(svc == null ? Color.RED : Color.BLACK);
    }

    private boolean openNotifSettings() {
        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            return true;
        } catch (Throwable t) { return false; }
    }

    private interface Svc { void run(CastService svc); }

    private void withSvc(Svc body) {
        CastService svc = CastService.inst();
        if (svc == null) { toast("后台服务还在启动，稍等一下"); return; }
        body.run(svc);
    }

    private void pickApp() {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(Intent.CATEGORY_LAUNCHER);
        final List<ResolveInfo> items = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(probe, 0)) {
            if (!getPackageName().equals(r.activityInfo.packageName)) items.add(r);
        }
        final Collator coll = Collator.getInstance();
        items.sort((x, y) -> coll.compare(x.loadLabel(pm), y.loadLabel(pm)));
        List<String> labels = new ArrayList<>();
        for (ResolveInfo r : items) labels.add(r.loadLabel(pm) + "  (" + r.activityInfo.packageName + ")");

        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        new AlertDialog.Builder(this)
                .setTitle("选择要投到仪表屏的应用")
                .setAdapter(ad, (d, which) -> {
                    ResolveInfo r = items.get(which);
                    ApplicationInfo ai = r.activityInfo.applicationInfo;
                    String name = pm.getApplicationLabel(ai).toString();
                    cfg.setTarget(r.activityInfo.packageName, r.activityInfo.name, name);
                    toast("已选定：" + name);
                    refresh();
                })
                .show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override public void onLog(String s) { handler.post(() -> log.setText(s)); }
}
