package com.jietu.clustercast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.ImageAdjustFloatingWindow;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.remote.handler.RemoteCommandHandler;
import android.os.Build;
import android.os.HandlerThread;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.QuadComposer;
import com.kooo.evcam.camera.SingleCamera;

import java.io.File;
import java.io.FileOutputStream;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 主屏设置页 —— 深色主题。
 * 布局：左侧应用网格（4 列，上图标下名称），右侧侧边栏（标题、投屏按钮、设置、日志）。
 */
public class MainActivity extends androidx.fragment.app.FragmentActivity implements CastService.LogSink {

    private Cfg cfg;
    private TextView tvStatus;
    private TextView tvAppCount;
    private TextView tvLog;
    private ScrollView svLog;
    private GridView grid;
    private AppAdapter adapter;
    private FrameLayout rightPanel;
    private FrameLayout pager;
    private LinearLayout topBarView;
    private View[] pages = new View[5];
    private TextView[] tabs = new TextView[5];
    private View[] tabLines = new View[5];
    private TextView winStatus, blindStatus, dvrStatus;
    private LinearLayout settingsPage, settingsBody;
    private ScrollView settingsSv;
    private View settingsView = null;
    private boolean inSettings = false;
    private TextView gearView;
    private String lastGearText = "";
    private TextView busView;
    private List<ResolveInfo> allApps = new ArrayList<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };
    private boolean sinkBound = false;

    @Override protected void onCreate(Bundle b) {
        Ui.fit1050(this);
        super.onCreate(b);
        // 全屏：透明状态栏/导航栏 + 隐藏系统栏，避免底部白条
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        cfg = new Cfg(this);
        Ui.uiScale = cfg.uiScale();
        instance = this;
        appConfig = new com.kooo.evcam.AppConfig(this);
        dingTalkConfig = new com.kooo.evcam.dingtalk.DingTalkConfig(this);
        telegramConfig = new com.kooo.evcam.telegram.TelegramConfig(this);
        feishuConfig = new com.kooo.evcam.feishu.FeishuConfig(this);

        allApps = loadApps();
        setContentView(buildUi());
        refresh();
        startBusCheck();
        // Device Owner 防杀加固（幂等）：防强行停止/防卸载/省电豁免/静默权限，
        // 顺带把 CAMERA/存储等运行时权限静默置为永久 GRANTED（弹窗链路作兜底）
        com.jietu.clustercast.KeepAliveGuard.apply(this);
        // 盲区/记录仪的 Camera2 通道：普通应用运行时弹窗授权一次即可（实测 USER_SET 永久记住）
        // 存储权限一并请求：U 盘录制走公共目录（U盘/DCIM/EVCam_Video），
        // 缺 WRITE_EXTERNAL_STORAGE 时 U 盘目录创建/写入全部被拒，会被误判为"检测不到U盘"
        java.util.ArrayList<String> perms = new java.util.ArrayList<>();
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            perms.add(android.Manifest.permission.CAMERA);
        }
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            perms.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[0]), 1);
        }
        maybeAutoStartRecording(getIntent());
        // 手动打开（非开机链路）："启动自动录制"开关管这里；开机链路归"开机自动录像"开关（maybeAutoStartRecording）
        Intent launch = getIntent();
        boolean fromBoot = launch != null && launch.getBooleanExtra("auto_start_from_boot", false);
        if (!fromBoot && appConfig.isAutoStartRecording()) {
            scheduleQuadStart("启动四合一录像");
        }
        // 远程指令执行层：移植时 initRemoteCommandDispatcher 从未被调用，
        // remote_action extras 也没人读——所有远程录制/拍照指令都会无声失败
        initRemoteCommandDispatcher();
        wireRemoteCallbacks();
        registerRemoteBroadcasts();
        restartStorageCleanupTask();
        handleRemoteAction(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        maybeAutoStartRecording(intent);
        handleRemoteAction(intent);
    }

    private final android.content.BroadcastReceiver remoteReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            String a = intent == null ? null : intent.getAction();
            if (com.kooo.evcam.WakeUpHelper.ACTION_MOVE_TO_BACKGROUND.equals(a)) {
                moveTaskToBack(true);
            } else if ("com.kooo.evcam.action.TOGGLE_RECORDING".equals(a)) {
                // 悬浮录制按钮：切四合一录制
                ui.post(new Runnable() { @Override public void run() {
                    if (quadRecording) stopQuad(); else startQuad();
                }});
            }
        }
    };

    private void registerRemoteBroadcasts() {
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(com.kooo.evcam.WakeUpHelper.ACTION_MOVE_TO_BACKGROUND);
        f.addAction("com.kooo.evcam.action.TOGGLE_RECORDING");
        registerReceiver(remoteReceiver, f);
    }

    /** 消费开机服务/WakeUpHelper 发来的 remote_action（此前 6 处发送 0 处接收）。 */
    private void handleRemoteAction(Intent i) {
        if (i == null || remoteCommandDispatcher == null) return;
        String action = i.getStringExtra("remote_action");
        if (action == null) return;
        // WakeUpHelper 标记本次是远程唤醒：任务结束后据此静默退回后台（原版契约）
        if (i.getBooleanExtra("remote_wake_up", false)) isRemoteWakeUp = true;
        syncApiClientsFromRemoteServiceManager();
        if ("record".equals(action)) {
            dispatchWhenCamerasReady(new Runnable() { @Override public void run() {
                String source = i.getStringExtra("remote_source");
                int duration = i.getIntExtra("remote_duration", 60);
                if ("feishu".equals(source)) {
                    remoteCommandDispatcher.startFeishuRecording(
                            i.getStringExtra("feishu_chat_id"), duration);
                } else if ("telegram".equals(source)) {
                    remoteCommandDispatcher.startTelegramRecording(
                            i.getLongExtra("telegram_chat_id", 0), duration);
                } else {
                    remoteCommandDispatcher.startDingTalkRecording(
                            i.getStringExtra("remote_conversation_id"),
                            i.getStringExtra("remote_conversation_type"),
                            i.getStringExtra("remote_user_id"), duration);
                }
            }});
        } else if ("photo".equals(action)) {
            dispatchWhenCamerasReady(new Runnable() { @Override public void run() {
                String source = i.getStringExtra("remote_source");
                if ("feishu".equals(source)) {
                    remoteCommandDispatcher.startFeishuPhoto(i.getStringExtra("feishu_chat_id"));
                } else if ("telegram".equals(source)) {
                    remoteCommandDispatcher.startTelegramPhoto(i.getLongExtra("telegram_chat_id", 0));
                } else {
                    remoteCommandDispatcher.startDingTalkPhoto(
                            i.getStringExtra("remote_conversation_id"),
                            i.getStringExtra("remote_conversation_type"),
                            i.getStringExtra("remote_user_id"));
                }
            }});
        } else if ("start_recording".equals(action)) {
            ui.post(new Runnable() { @Override public void run() { startRecording(); } });
        } else if ("stop_recording".equals(action)) {
            ui.post(new Runnable() { @Override public void run() {
                if (quadRecording) stopQuad();
            }});
        } else if ("restart_app".equals(action)) {
            ui.post(new Runnable() { @Override public void run() { restartApp(); }});
        } else if ("exit_app".equals(action)) {
            runOnUiThread(new Runnable() { @Override public void run() { exitApp(); }});
        }
        // "foreground"：Activity 已被拉到前台，无需额外动作
    }

    /** 远程录制/拍照前确保四路相机已打开：后台被唤醒时相机可能是关闭的
     *  （onPause 会按页签释放相机），直接派发会被"没有可用的相机"拒绝。 */
    private void dispatchWhenCamerasReady(final Runnable dispatch) {
        ui.post(new Runnable() { @Override public void run() {
            if (!isFinishing()) switchTab(4);
        }});
        final long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
        final Runnable[] poll = new Runnable[1];
        poll[0] = new Runnable() { @Override public void run() {
            if (isFinishing()) return;
            ensureMcm();
            if (mcm.hasConnectedCameras()) {
                dispatch.run();
            } else if (android.os.SystemClock.elapsedRealtime() < deadline) {
                ui.postDelayed(poll[0], 400);
            } else {
                // 超时也派发，让 RemoteCommandHandler 把错误回给发送方
                dispatch.run();
            }
        }};
        ui.postDelayed(poll[0], 800);
    }

    /** 本进程只自动开录一次（开机链路/手动打开共用），用户手动停止后不再自动重开。 */
    private boolean autoStartRecordingDone;

    /**
     * 延迟开四合一录像：等界面与相机初始化稳定。
     * @param why 状态栏提示语
     */
    private void scheduleQuadStart(final String why) {
        if (autoStartRecordingDone || quadRecording) return;
        autoStartRecordingDone = true;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (quadRecording || isFinishing()) return;
                if (dvrStatus != null) {
                    note(dvrStatus, "自动录制：" + why);
                }
                startQuad();
            }
        }, 2500);
    }

    /** 开机自启/服务重启路径：CameraForegroundService、TransparentBootActivity 带
     *  auto_start_from_boot 拉起本页（silent_mode 不可见），按"开机自动录像"开关
     *  延迟开四合一录像。手动打开的自动录制在 onCreate 里按"启动自动录制"开关走。 */
    private void maybeAutoStartRecording(Intent i) {
        if (i == null || !i.getBooleanExtra("auto_start_from_boot", false)) return;
        if (appConfig == null || !appConfig.isBootAutoRecord()) return;
        scheduleQuadStart("启动四合一录像（开机自动录像）");
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        CastService.start(this);
        // 远程通道跟随应用启动自动连接（isConfigured/isAutoStart 在 manager 内把关）
        ui.post(new Runnable() { @Override public void run() {
            com.kooo.evcam.RemoteServiceManager.getInstance()
                    .startRemoteServicesFromService(MainActivity.this);
        }});
    }

    @Override protected void onResume() {
        super.onResume();
        isInBackground = false;
        ui.removeCallbacks(tick);
        ui.post(tick);
        enterTab(curTab);
    }

    @Override protected void onPause() {
        super.onPause();
        isInBackground = true;
        ui.removeCallbacks(tick);
        CastService s = CastService.inst();
        if (s != null) s.setSink(null);
        sinkBound = false;
        // 环视预览跟前台走；录像中只停预览、录像输出继续（同一批相机实例）
        for (CamStream h : streams) {
            if (QuadAutoRecord.owns(h.s) || (quadRecording && h.page == 4)) h.s.keepRecordingOnly();
            else h.s.stop();
        }
        if (quadRecording) {
            if (QuadAutoRecord.isActive()) {
                // 服务侧自动录像（或界面接管它）：退后台/息屏照录，
                // 它就是为无界面持续录制设计的
            } else {
                // 自己开的录制：前台相机服务（camera type）在位时退后台继续录；
                // 息屏时按"息屏录制"开关决定，CPU 由 CameraForegroundService 的
                // PARTIAL_WAKE_LOCK 保持。录制中绝不 pause 相机（会把录像流一起断）
                boolean interactive = ((android.os.PowerManager) getSystemService(POWER_SERVICE))
                        .isInteractive();
                if (!interactive && !appConfig.isScreenOffRecordingEnabled()) {
                    stopQuad();
                    if (mcm != null) mcm.pauseAllCamerasByLifecycle();
                }
            }
        } else if (mcm != null) {
            // 相机/录制跟前台走：退后台释放（close 同时抑制 FGS 修复循环，
            // 否则后台每 10s 会把摄像头重新拉起白占资源）
            mcm.closeAllCameras();
        }
    }

    @Override public void onBackPressed() {
        if (menuPanel != null && menuPanel.getVisibility() == View.VISIBLE) {
            menuPanel.setVisibility(View.GONE);
            setPagerShown(true);
            return;
        }
        if (evcamFragment != null) {
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                getSupportFragmentManager().popBackStack();
            } else {
                closeEvcamFragment();
            }
            return;
        }
        if (inSettings) {
            closeSettingsOverlay();
            return;
        }
        // 超视浮窗开着时，第一次返回先收浮窗而不是退出应用
        if (appConfig.isSupervisionModeEnabled()) {
            toggleSupervisionMode();
            return;
        }
        super.onBackPressed();
    }

    // ---------- EVCam 功能片段覆盖层（软件设置/补盲/远程查看/回放等） ----------

    private androidx.fragment.app.Fragment evcamFragment;
    private int evcamContainerId;
    private FrameLayout evcamOverlay;

    private void showEvcamFragment(androidx.fragment.app.Fragment f, String title) {
        if (inSettings) closeSettingsOverlay();
        if (evcamFragment != null) closeEvcamFragment();

        if (evcamOverlay == null) {
            evcamOverlay = new FrameLayout(this);
            ((FrameLayout) findViewById(android.R.id.content)).addView(evcamOverlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            evcamOverlay.setVisibility(View.GONE);
        } else if (evcamOverlay.getParent() == null) {
            ((FrameLayout) findViewById(android.R.id.content)).addView(evcamOverlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        // 顶栏常驻：EVCam 覆盖层从顶栏下面开始铺，别把顶栏盖掉
        if (topBarView != null) {
            FrameLayout.LayoutParams elp = (FrameLayout.LayoutParams) evcamOverlay.getLayoutParams();
            elp.topMargin = topBarView.getHeight();
            evcamOverlay.setLayoutParams(elp);
        }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Ui.darkBg(this, 0x33151A24, 12));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        TextView btnBack = Ui.darkButton(this, "← 返回", 14, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(btnBack, new Runnable() {
            @Override public void run() { closeEvcamFragment(); }
        });
        bar.addView(btnBack, Ui.ww());
        bar.addView(hsp(10));
        TextView headTitle = Ui.text(this, 16, Ui.D_TEXT, Typeface.BOLD, 1);
        headTitle.setText(title);
        bar.addView(headTitle, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(bar, Ui.lw());

        FrameLayout container = new FrameLayout(this);
        evcamContainerId = R.id.fragment_container;
        container.setId(evcamContainerId);
        page.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        evcamOverlay.removeAllViews();
        evcamOverlay.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        evcamOverlay.setVisibility(View.VISIBLE);
        // 浮层是透明的，pager 不藏会把底下的页面透出来
        setPagerShown(false);
        evcamFragment = f;
        getSupportFragmentManager().beginTransaction()
                .replace(evcamContainerId, f).commitAllowingStateLoss();
    }

    /** 功能浮层/菜单打开时把 pager 藏起来，露出壁纸而不是上一页。 */
    private void setPagerShown(boolean shown) {
        if (pager != null)
            pager.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    private void closeEvcamFragment() {
        if (evcamFragment == null) return;
        androidx.fragment.app.Fragment f = evcamFragment;
        evcamFragment = null;
        getSupportFragmentManager().popBackStack(null,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        getSupportFragmentManager().beginTransaction().remove(f).commitAllowingStateLoss();
        if (evcamOverlay != null) evcamOverlay.setVisibility(View.GONE);
        if (menuPanel == null || menuPanel.getVisibility() != View.VISIBLE)
            setPagerShown(true);
    }

    /** 超视模式：左右两路补盲悬浮窗（自 EVCam toggleSupervisionMode 移植） */
    private void toggleSupervisionMode() {
        boolean newEnabled = !appConfig.isSupervisionModeEnabled();
        appConfig.setSupervisionModeEnabled(newEnabled);
        Toast.makeText(this, newEnabled ? "超视模式已开启" : "超视模式已关闭",
                Toast.LENGTH_SHORT).show();
        Intent i = new Intent("com.kooo.evcam.SUPERVISION_MODE_CHANGED");
        i.putExtra("enabled", newEnabled);
        sendBroadcast(i);
        Intent si = new Intent(this, com.kooo.evcam.BlindSpotService.class);
        si.setAction(newEnabled ? "START_SUPERVISION_MODE" : "STOP_SUPERVISION_MODE");
        startService(si);
        AppLog.d(TAG, "超视模式切换: " + newEnabled);
    }

    // ---------- 构建 ----------

    private View buildUi() {
        // 根：竖向 = 固定顶栏（4 键 + 5 页签 + 挡位总线，常驻所有界面） + 内容页
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackground(Ui.photoWallpaper(this));

        // ===== 固定顶栏：左 4 圆形图标键 | 中 5 页签（选中粉线） | 右 挡位+总线 =====
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        topBar.setBackground(Ui.darkBg(this, Ui.D_CARD, 0));

        // 左：返回主页 / 关闭软件 / 设置 / 调整大小（图标取自应用管家）
        topBar.addView(topIcon(R.drawable.tp_zy1, new Runnable() {
            @Override public void run() { goHome(); }
        }));
        topBar.addView(topIcon(R.drawable.tp_close1, new Runnable() {
            @Override public void run() { exitApp(); }
        }));
        topBar.addView(topIcon(R.drawable.shezhi3, new Runnable() {
            @Override public void run() { showSettingsOverlay(); }
        }));
        topBar.addView(topIcon(R.drawable.tp_sf3, new Runnable() {
            @Override public void run() { showScaleDialog(); }
        }));

        // 中：自己的 5 页签，选中项下方粉红色高亮线（仿应用管家 item_menu）
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.HORIZONTAL);
        menu.setGravity(Gravity.CENTER);
        String[] names = {"投屏", "空调", "车窗", "盲区", "记录仪"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 2));
            TextView t = Ui.text(this, 15, 0xFFFFFFFF, Typeface.NORMAL, 1);
            t.setText(names[i]);
            item.addView(t, Ui.ww());
            View line = new View(this);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 40), Ui.dp(this, 2));
            llp.topMargin = Ui.dp(this, 2);
            line.setLayoutParams(llp);
            item.addView(line);
            Ui.click(item, new Runnable() {
                @Override public void run() { switchTab(idx); }
            });
            menu.addView(item, Ui.ww());
            tabs[i] = t;
            tabLines[i] = line;
        }
        topBar.addView(menu, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右：挡位 + 总线状态（每屏可见）
        int chPadX = Ui.dp(this, 10), chPadY = Ui.dp(this, 4);
        gearView = Ui.text(this, 11, Ui.D_TEXT_SUB, Typeface.NORMAL, 1);
        gearView.setText("挡位 --");
        gearView.setBackground(Ui.darkBg(this, Ui.D_BTN, 14));
        gearView.setPadding(chPadX, chPadY, chPadX, chPadY);
        topBar.addView(gearView, Ui.ww());
        busView = Ui.text(this, 11, Ui.D_TEXT_SUB, Typeface.NORMAL, 1);
        busView.setText("总线检查中…");
        busView.setBackground(Ui.darkBg(this, Ui.D_BTN, 14));
        busView.setPadding(chPadX, chPadY, chPadX, chPadY);
        LinearLayout.LayoutParams busLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        busLp.leftMargin = Ui.dp(this, 6);
        topBar.addView(busView, busLp);
        topBarView = topBar;
        outer.addView(topBar, Ui.lw());
        pager = new FrameLayout(this);

        // ===== 页 0：投屏（默认页，原两栏布局：左 = 侧边栏，右 = 应用网格） =====
        LinearLayout castPage = new LinearLayout(this);
        castPage.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Ui.dp(this, 12);
        castPage.setPadding(pad, pad, pad, pad);

        // ===== 左侧：侧边栏 =====
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackground(Ui.darkBg(this, Ui.D_CARD, 12));
        int rpad = Ui.dp(this, 12);
        left.setPadding(rpad, rpad, rpad, rpad);
        castPage.addView(left, Ui.weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT));

        // 标题
        TextView appTitle = Ui.text(this, 18, Ui.D_TEXT, Typeface.BOLD, 1);
        appTitle.setText("冥城车机工具箱");
        left.addView(appTitle, Ui.lw());
        left.addView(vsp(10));

        // 应用数量
        tvAppCount = Ui.text(this, 12, Ui.D_TEXT_SUB, Typeface.NORMAL, 1);
        tvAppCount.setText("应用列表  共 " + allApps.size() + " 个应用");
        left.addView(tvAppCount, Ui.lw());
        left.addView(vsp(10));

        // 状态文字
        tvStatus = Ui.text(this, 12, Ui.D_TEXT_SUB, Typeface.NORMAL, 2);
        left.addView(tvStatus, Ui.lw());
        left.addView(vsp(10));

        // 开始投屏按钮（绿色，透明化但保留色相）
        TextView btnCast = Ui.darkButton(this, "开始投屏", 15, Ui.D_GREEN, 0xFFFFFFFF);
        btnCast.setBackground(Ui.darkBg(this, Ui.D_GREEN_T, 10));
        Ui.click(btnCast, new Runnable() {
            @Override public void run() {
                CastService s = CastService.inst();
                if (s == null) { toast("服务还在启动，两秒后再试"); return; }
                s.castNow();
            }
        });
        left.addView(btnCast, Ui.lw());
        left.addView(vsp(8));

        // 结束投屏按钮（红色，透明化但保留色相）
        TextView btnExit = Ui.darkButton(this, "结束投屏", 15, Ui.D_DANGER, 0xFFFFFFFF);
        btnExit.setBackground(Ui.darkBg(this, Ui.D_DANGER_T, 10));
        Ui.click(btnExit, new Runnable() {
            @Override public void run() {
                CastService s = CastService.inst();
                if (s == null) { toast("服务还没起来"); return; }
                s.exitNow();
            }
        });
        left.addView(btnExit, Ui.lw());
        left.addView(vsp(8));

        // 日志区标题行
        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView lt = Ui.text(this, 13, Ui.D_TEXT, Typeface.BOLD, 1);
        lt.setText("全部日志");
        logHead.addView(lt, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView btnClear = Ui.darkButton(this, "清空", 11, Ui.D_BTN, Ui.D_TEXT_SUB);
        Ui.click(btnClear, new Runnable() {
            @Override public void run() {
                CastService s = CastService.inst();
                if (s != null) s.clearLog();
                if (tvLog != null) tvLog.setText("");
            }
        });
        logHead.addView(btnClear, Ui.ww());
        left.addView(logHead, Ui.lw());
        left.addView(vsp(6));

        // 日志区
        svLog = new ScrollView(this);
        svLog.setFillViewport(true);
        svLog.setBackground(Ui.darkBg(this, Ui.D_FIELD, 8));
        tvLog = Ui.text(this, 10.5f, Ui.D_TEXT_SUB, Typeface.NORMAL, -1);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        svLog.addView(tvLog, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        left.addView(svLog, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ===== 右侧：应用网格（FrameLayout 用于覆盖设置页） =====
        rightPanel = new FrameLayout(this);
        LinearLayout.LayoutParams rlp = Ui.weighted(2.5f, ViewGroup.LayoutParams.MATCH_PARENT);
        rlp.leftMargin = Ui.dp(this, 10);
        castPage.addView(rightPanel, rlp);

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(this, 14));
        grid.setVerticalSpacing(Ui.dp(this, 14));
        grid.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        grid.setBackground(Ui.darkBg(this, Ui.D_CARD, 12));
        adapter = new AppAdapter(allApps);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v,
                    int pos, long id) {
                ResolveInfo r = adapter.getItem(pos);
                chooseApp(r);
            }
        });
        rightPanel.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pages[0] = castPage;
        pager.addView(castPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ===== 页 1-4：空调 / 车窗 / 盲区 / 记录仪 =====
        // 页 1 空调：自绘页已删，页签只拉起原车空调应用，这里留空白页占位
        pages[1] = new LinearLayout(this);
        pages[2] = buildWinPage();
        pages[3] = buildBlindPage();
        pages[4] = buildDvrPage();
        for (int i = 1; i < pages.length; i++) {
            pages[i].setVisibility(View.GONE);
            pager.addView(pages[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        // 竖向外层：权重分配高度，宽度必须 MATCH_PARENT（Ui.weighted 的 0 宽写法只适用横向）
        outer.addView(pager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        applyTab(0);
        return outer;
    }

    // ---------- 顶栏辅助 ----------

    /** 顶栏圆形图标键：透明圆底 + 白描边（仿应用管家 btn_home_focus 常态）。 */
    private ImageView topIcon(int res, Runnable action) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        int s = Ui.dp(this, 34);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = Ui.dp(this, 7);
        iv.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) iv.getLayoutParams();
        lp.rightMargin = Ui.dp(this, 8);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0x00000000);
        bg.setStroke(Ui.dp(this, 1), 0x66FFFFFF);
        iv.setBackground(bg);
        Ui.click(iv, action);
        return iv;
    }

    /** 返回系统主页（等价按 HOME 键，本应用驻后台继续服务）。 */
    private void goHome() {
        try {
            Intent h = new Intent(Intent.ACTION_MAIN);
            h.addCategory(Intent.CATEGORY_HOME);
            h.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(h);
        } catch (Throwable t) {
            Toast.makeText(this, "无法返回主页", Toast.LENGTH_SHORT).show();
        }
    }

    /** 调整界面大小：整体缩放 dp/字号，Cfg 持久化，重建生效。 */
    private void showScaleDialog() {
        final float[] vals = {0.85f, 1.0f, 1.15f, 1.3f};
        final String[] items = {"小", "标准", "大", "特大"};
        new AlertDialog.Builder(this)
                .setTitle("界面大小")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (Ui.uiScale == vals[w]) return;
                        Ui.uiScale = vals[w];
                        cfg.setUiScale(vals[w]);
                        recreate();
                    }
                })
                .show();
    }

    // ---------- 底部导航 ----------

    private void switchTab(int idx) {
        if (evcamFragment != null) closeEvcamFragment();
        // 空调页签只拉起原车空调应用（自绘页已删）
        if (idx == 1) {
            if (launchOriginalAcApp()) {
                applyTab(curTab);
            } else {
                Toast.makeText(this, "没有找到原车空调", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        for (int i = 0; i < pages.length; i++)
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        curTab = idx;
        applyTab(idx);
        enterTab(idx);
    }

    /** 拉起原车空调应用：实车 T1J 的空调是 com.desaysv.svhvac/.activity.T1NHvacActivity，
     *  它没挂 LAUNCHER 分类，按 LAUNCHER 查询查不到 —— 先显式直拉，失败再按名称模糊匹配。 */
    private boolean launchOriginalAcApp() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN)
                    .setClassName("com.desaysv.svhvac", "com.desaysv.svhvac.activity.T1NHvacActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Toast.makeText(this, "已打开原车空调", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Throwable t) {
            AppLog.d(TAG, "直拉原车空调失败: " + t);
        }
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (getPackageName().equals(pkg)) continue;
                String label = String.valueOf(ri.loadLabel(pm));
                String low = pkg.toLowerCase(Locale.US);
                if (label.contains("空调") || low.contains("hvac")
                        || low.contains("aircondition") || low.contains("kongtiao")) {
                    Intent i = pm.getLaunchIntentForPackage(pkg);
                    if (i == null) continue;
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    Toast.makeText(this, "已打开原车空调", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }
        } catch (Throwable t) {
            AppLog.d(TAG, "拉起原车空调失败: " + t);
        }
        return false;
    }

    /** 摄像头生命周期跟页签走：只动当前页的流（盲区/记录仪共用同一批摄像头 id）；
     *  记录仪页录像中离开只停预览、录像输出继续。 */
    private void enterTab(int idx) {
        if (idx != 3) blindZoomOut();
        for (CamStream h : streams) {
            if (idx == h.page) {
                // 远程指令的 MCM 录像在跑时别开本页流：同一批摄像头会被抢
                if (h.page == 4 && mcm != null && mcm.isRecording()) continue;
                if (h.tex != null) h.s.start(h.tex);
            } else if (QuadAutoRecord.owns(h.s) || (quadRecording && h.page == 4)) {
                // 自动录像占用中 / 记录仪录像中离开：只停预览，录像输出继续
                h.s.keepRecordingOnly();
            } else {
                h.s.stop();
            }
        }
        // 页签与轮询的挂载：idx2=车窗/尾门（之前尾门轮询错挂在空调页，
        // 进车窗页反而被停掉，所以尾门状态永远停在"读取中"）
        if (idx == 2) {
            startTailgatePoll();
        } else {
            stopTailgatePoll();
        }
        if (idx == 4) {
            // 原车在前台相机服务没起来时会以 CAMERA_DISABLED 拦开流
            com.kooo.evcam.CameraForegroundService.start(this, "行车记录仪", "保持相机可调用");
        }
    }

    private void applyTab(int idx) {
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == idx;
            tabs[i].setTextColor(on ? 0xFFFFFFFF : 0xCCFFFFFF);
            // 选中页签下方粉红色高亮线（应用管家 color_17 = #ff00ff）
            tabLines[i].setBackgroundColor(on ? 0xFFFF00FF : Color.TRANSPARENT);
        }
    }

    // ---------- 通用页骨架 ----------

    /** 标题 + 状态行（兼作操作反馈），返回页面容器。 */
    private LinearLayout pageFrame(String title, String subtitle, TextView[] statusOut) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        int p14 = Ui.dp(this, 14);
        p.setPadding(p14, p14, p14, p14);
        TextView t = Ui.text(this, 20, Ui.D_TEXT, Typeface.BOLD, 1);
        t.setText(title);
        p.addView(t, Ui.lw());
        p.addView(vsp(4));
        TextView s = Ui.text(this, 12, Ui.D_TEXT_SUB, Typeface.NORMAL, 3);
        s.setText(subtitle);
        p.addView(s, Ui.lw());
        p.addView(vsp(12));
        statusOut[0] = s;
        return p;
    }

    private void note(TextView status, String s) {
        if (status != null) status.setText(s);
    }

    private TextView rowLabel(String s) {
        return Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
    }

    private TextView stepBtn(String s) {
        TextView b = Ui.darkButton(this, s, 15, Ui.D_BTN, Ui.D_TEXT);
        b.setMinWidth(Ui.dp(this, 52));
        return b;
    }

    // ---------- 车窗 / 尾门页（真总线：moduleId 327681，协议见 协议说明-车窗尾门后视镜.md） ----------

    private static final int EV_BODY = 327681;

    private View buildWinPage() {
        TextView[] st = new TextView[1];
        LinearLayout p = pageFrame("车窗 / 尾门", "", st);
        winStatus = st[0];
        // 标题/状态留在页头，卡片整体塞进 ScrollView：dp 偏大屏幕放不下时也能滚动看完
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String[][] doors = {{"主驾", "162"}, {"副驾", "163"}, {"左后", "164"}, {"右后", "165"}};
        for (final String[] d : doors) {
            LinearLayout card = Ui.darkCard(this, 12);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView lbl = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
            lbl.setText(d[0]);
            row.addView(lbl, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
            winBtn(row, d[0] + "全开", Integer.parseInt(d[1]), 2);
            winBtn(row, d[0] + "透气", Integer.parseInt(d[1]), 3);
            winBtn(row, d[0] + "全关", Integer.parseInt(d[1]), 1);
            card.addView(row, Ui.lw());
            body.addView(card, Ui.lw());
            body.addView(vsp(10));
        }

        // 一键四窗：cmdId 175 发 int[4]，四元素同值时下标排布无关紧要
        LinearLayout cardAll = Ui.darkCard(this, 12);
        cardAll.addView(rowLabel("一键四窗（cmdId 175）"));
        cardAll.addView(vsp(8));
        LinearLayout rowAll = new LinearLayout(this);
        rowAll.setOrientation(LinearLayout.HORIZONTAL);
        allBtn(rowAll, "全部打开", 2);
        allBtn(rowAll, "全部透气", 3);
        allBtn(rowAll, "全部关闭", 1);
        cardAll.addView(rowAll, Ui.lw());
        body.addView(cardAll, Ui.lw());
        body.addView(vsp(10));

        // 电动尾门：状态读 327684/162（1=开 0=关，2026-09-29 实车扫描标定）；
        // 开/关控制下发 327681/92=1（92 是动作信号，读数 2=锁闭 0=非锁闭，别拿它当开关状态）
        LinearLayout cardTail = Ui.darkCard(this, 12);
        cardTail.addView(rowLabel("电动尾门（cmdId 92 控制 / 162 状态）"));
        cardTail.addView(vsp(8));
        LinearLayout rowTail = new LinearLayout(this);
        rowTail.setOrientation(LinearLayout.HORIZONTAL);
        rowTail.setGravity(Gravity.CENTER_VERTICAL);
        tailgateState = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        tailgateState.setText("尾门状态：读取中…");
        rowTail.addView(tailgateState, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView bt2 = Ui.darkButton(this, "尾门 开/关", 14, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(bt2, new Runnable() {
            @Override public void run() { carWrite(EV_BODY, 92, 1, "尾门开/关", winStatus); }
        });
        bt2.setMinWidth(Ui.dp(this, 110));
        rowTail.addView(bt2, Ui.ww());
        cardTail.addView(rowTail, Ui.lw());
        body.addView(cardTail, Ui.lw());
        p.addView(sv, Ui.lw());
        return p;
    }

    // 尾门状态轮询：车窗页可见时每 2 秒回读 327684/162（1=开 0=关）
    private TextView tailgateState;
    private Thread tailgateThread;

    private void startTailgatePoll() {
        if (tailgateThread != null) return;
        final android.content.Context ctx = this;
        tailgateThread = new Thread(new Runnable() {
            @Override public void run() {
                while (tailgateThread != null && !Thread.currentThread().isInterrupted()) {
                    Vd v = Vd.connect(ctx);
                    final int back = v.getItem(327684, 162);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (tailgateState == null) return;
                            if (back == 1) tailgateState.setText("尾门状态：已打开");
                            else if (back == 0) tailgateState.setText("尾门状态：已关闭");
                            else if (back < 0) tailgateState.setText("尾门状态：读取不到（检查总线）");
                            else tailgateState.setText("尾门状态：" + back);
                        }
                    });
                    try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                }
            }
        }, "tailgate-poll");
        tailgateThread.start();
    }

    private void stopTailgatePoll() {
        Thread t = tailgateThread;
        tailgateThread = null;
        if (t != null) t.interrupt();
    }

    /** 单窗按钮：162~165，值 1=关 2=开 3=透气。 */
    private void winBtn(LinearLayout row, final String label, final int cmdId, final int value) {
        TextView b = Ui.darkButton(this, label, 13, Ui.D_BTN, Ui.D_TEXT);
        b.setMinWidth(Ui.dp(this, 66));
        Ui.click(b, new Runnable() {
            @Override public void run() { carWrite(EV_BODY, cmdId, value, label, winStatus); }
        });
        row.addView(b, Ui.ww());
        row.addView(hsp(6));
    }

    /** 一键四窗按钮：175 的 int[4] 同值发四份。 */
    private void allBtn(LinearLayout row, final String label, final int value) {
        TextView b = Ui.darkButton(this, label, 14, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(b, new Runnable() {
            @Override public void run() {
                carWriteArr(EV_BODY, 175, new int[]{value, value, value, value}, label, winStatus);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = Ui.dp(MainActivity.this, 6);
        row.addView(b, lp);
    }

    /**
     * 车控写 + 多轮回读校验（照 vehprobe 节奏：0.3/1/2s 各试一次），结果写状态行。
     * 全程子线程（VDBus 是同步 binder），结果回主线程。
     */
    private void carWrite(final int eventId, final int cmdId, final int value,
                          final String what, final TextView status) {
        carWriteArr(eventId, cmdId, new int[]{value}, what, status);
    }

    private void carWriteArr(final int eventId, final int cmdId, final int[] values,
                             final String what, final TextView status) {
        new Thread(new Runnable() {
            @Override public void run() {
                Vd v = Vd.connect(MainActivity.this);
                final String err = v.sendItems(eventId, cmdId, values);
                String msg;
                if (err != null) {
                    msg = what + " 下发失败：" + err;
                } else {
                    int back = -1;
                    long[] waits = {300, 700, 1000};
                    for (long w : waits) {
                        try { Thread.sleep(w); } catch (InterruptedException ignored) { }
                        back = v.getItem(eventId, cmdId);
                        boolean hit = back == values[0];
                        if (hit) break;
                    }
                    if (back == values[0]) msg = what + "：已生效（回读 " + back + "）";
                    else if (back < 0) msg = what + "：已下发，回读不到（检查总线状态）";
                    else msg = what + "：已下发，回读 " + back + "（暂未到 " + values[0] + "）";
                }
                final String s = msg;
                ui.post(new Runnable() {
                    @Override public void run() { note(status, s); }
                });
            }
        }, "car-write").start();
    }

    // ---------- 盲区页（四路环视 Camera2 取流，id 4=前 5=右 6=左 7=后） ----------

    /** 一路环视流 + 它的 SurfaceTexture（页签隐藏再回来时复用同一个纹理重开流）。
     *  page：3=盲区页 4=记录仪页 —— 页签切换只动本页的流，两页共用同一批摄像头
     *  id，不能同时开两份。 */
    private static final class CamStream {
        Surround s;
        final int page;
        TextureView tv;
        SurfaceTexture tex;
        CamStream(Surround s, int page) { this.s = s; this.page = page; }
    }

    private final List<CamStream> streams = new ArrayList<>();
    private int curTab = 0;

    // ===== 四合一记录仪（Surround 直开 Camera2 预览 + QuadComposer 2×2 合成） =====
    private static final String[] QUAD_POS = {"front", "back", "left", "right"};
    /** pos 0~3（前/后/左/右）对应的环视摄像头 id，与盲区页 cams 表一致。 */
    private static final int[] QUAD_CAMS = {4, 7, 6, 5};
    private MultiCameraManager mcm;
    private QuadComposer quad;
    private boolean quadRecording;
    /** 当前会话是否接管自服务侧自动录像（ QuadAutoRecord ）：决定停止/退出语义。 */
    private boolean quadFromAuto;
    private TextureView[] quadViews;
    private final CamStream[] quadStreams = new CamStream[4];
    private TextView recBtn, quadTime;
    private long quadStartMs;
    private Runnable quadTicker;
    private LinearLayout menuPanel;

    private View buildBlindPage() {
        TextView[] st = new TextView[1];
        LinearLayout p = pageFrame("盲区影像", "", st);
        blindStatus = st[0];

        // 2×2 四宫格：第一排 前/后，第二排 左/右（样式与记录仪四合一一致）
        final LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        blindGrid = grid;
        int[][] cams = {{4, 7}, {6, 5}};
        String[][] names = {{"前", "后"}, {"左", "右"}};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 2; c++) {
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (c > 0) clp.leftMargin = Ui.dp(this, 6);
                row.addView(blindCell(names[r][c], cams[r][c]), clp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            if (r > 0) rlp.topMargin = Ui.dp(this, 6);
            grid.addView(row, rlp);
        }
        p.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 全屏放大层：点击宫格时把那一路的 TextureView 摘进来铺满
        blindZoom = new FrameLayout(this);
        blindZoom.setBackground(Ui.darkBg(this, Ui.D_BG, 0));
        blindZoom.setVisibility(View.GONE);
        Ui.click(blindZoom, new Runnable() { @Override public void run() { blindZoomOut(); }});
        TextView hint = Ui.text(this, 12, 0xB3FFFFFF, Typeface.NORMAL, 1);
        hint.setText("点击画面返回四宫格");
        hint.setGravity(Gravity.CENTER);
        hint.setBackground(Ui.darkBg(this, 0x80000000, 8));
        int hs = Ui.dp(this, 34);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, hs,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = Ui.dp(this, 12);
        blindZoom.addView(hint, hlp);
        p.addView(blindZoom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return p;
    }

    private LinearLayout blindGrid;
    private FrameLayout blindZoom;
    private final HashMap<Integer, TextureView> blindTvs = new HashMap<>();
    private final HashMap<Integer, FrameLayout> blindCells = new HashMap<>();
    private int blindZoomedCam = -1;

    /** 点击宫格 → 该路全屏；再点全屏 → 放回宫格。同一个摄像头不能开两路，
     *  所以是移动 TextureView 而非新建：摘下/挂回会自动触发停流/重开流。 */
    private void blindZoomIn(int camId) {
        TextureView tv = blindTvs.get(camId);
        FrameLayout cell = blindCells.get(camId);
        if (blindZoom == null || tv == null || cell == null) return;
        if (tv.getParent() instanceof ViewGroup) {
            ((ViewGroup) tv.getParent()).removeView(tv);
        }
        blindZoom.addView(tv, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        blindGrid.setVisibility(View.GONE);
        blindZoom.setVisibility(View.VISIBLE);
        blindZoomedCam = camId;
    }

    private void blindZoomOut() {
        if (blindZoomedCam < 0) return;
        int camId = blindZoomedCam;
        blindZoomedCam = -1;
        TextureView tv = blindTvs.get(camId);
        FrameLayout cell = blindCells.get(camId);
        if (tv != null && tv.getParent() == blindZoom) blindZoom.removeView(tv);
        if (tv != null && cell != null) {
            cell.addView(tv, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        blindZoom.setVisibility(View.GONE);
        if (blindGrid != null) blindGrid.setVisibility(View.VISIBLE);
    }

    /** 四宫格盲区格：黑底 TextureView 铺满 + 左上角汉字标签胶囊（与记录仪同样式）。
     *  点格子 = 该路全屏放大。 */
    private FrameLayout blindCell(final String label, final int camId) {
        final FrameLayout f = new FrameLayout(this);
        f.setBackground(Ui.darkBg(this, Ui.D_BG, 8));
        TextureView tv = camStream(camId, 3).tv;
        blindTvs.put(camId, tv);
        blindCells.put(camId, f);
        f.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView t = Ui.text(this, 16, 0xFFFFFFFF, Typeface.BOLD, 1);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.darkBg(this, 0x80000000, 8));
        int s = Ui.dp(this, 40);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s,
                Gravity.TOP | Gravity.START);
        lp.leftMargin = Ui.dp(this, 10);
        lp.topMargin = Ui.dp(this, 6);
        f.addView(t, lp);
        Ui.click(f, new Runnable() { @Override public void run() { blindZoomIn(camId); }});
        return f;
    }

    /** 建一路环视流：surface 就绪开流、销毁停流，失败写状态行。
     *  自动录像占用中时复用它的 Surround（双输出，预览挂上去录像不断）。 */
    private CamStream camStream(int camId, int page) {
        Surround owned = QuadAutoRecord.cam(camId);
        final CamStream cam = new CamStream(owned != null ? owned : new Surround(this, camId), page);
        cam.s.setReport(new Surround.Report() {
            @Override public void onStatus(final String msg) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        note(page == 3 ? blindStatus : dvrStatus, msg);
                    }
                });
            }
        });
        streams.add(cam);
        TextureView tv = new TextureView(this);
        cam.tv = tv;
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                cam.tex = st;
                cam.s.start(st);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (QuadAutoRecord.owns(cam.s) || (quadRecording && cam.page == 4)) {
                    cam.s.keepRecordingOnly();
                } else {
                    cam.s.stop();
                }
                cam.tex = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        return cam;
    }

    /** 照原版四大按钮 + 四宫格预览：顶部 ☰/○/◉/✕，下方 2×2 摄像区（前/后/左/右），
     *  右上角录像计时胶囊；☰ 展开功能入口面板。 */
    private View buildDvrPage() {
        TextView[] st = new TextView[1];
        LinearLayout p = pageFrame("行车记录仪", "", st);
        dvrStatus = st[0];

        quadViews = new TextureView[4];

        // 顶部原版四大按钮：☰ 菜单 / ○ 录像 / ◉ 拍照 / ✕ 退出
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView menuBtn = Ui.darkButton(this, "☰", 26, Ui.D_BTN, Ui.D_TEXT);
        recBtn = Ui.darkButton(this, "○", 26, Ui.D_BTN, 0xFFFF5252);
        TextView photoBtn = Ui.darkButton(this, "◉", 26, Ui.D_BTN, Ui.D_TEXT);
        TextView exitBtn = Ui.darkButton(this, "✕", 26, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(menuBtn, new Runnable() { @Override public void run() {
            if (menuPanel == null) return;
            if (menuPanel.getParent() == null) {
                ((FrameLayout) findViewById(android.R.id.content)).addView(menuPanel,
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
            }
            // 顶栏常驻：菜单面板从顶栏下面开始铺
            if (topBarView != null) {
                FrameLayout.LayoutParams mlp =
                        (FrameLayout.LayoutParams) menuPanel.getLayoutParams();
                mlp.topMargin = topBarView.getHeight();
                menuPanel.setLayoutParams(mlp);
            }
            boolean show = menuPanel.getVisibility() != View.VISIBLE;
            menuPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            // 菜单是 80% 透明的，pager 不藏会把记录仪页面透出来
            setPagerShown(!show);
        }});
        Ui.click(recBtn, new Runnable() { @Override public void run() {
            if (quadRecording) stopQuad(); else startQuad();
        }});
        Ui.click(photoBtn, new Runnable() { @Override public void run() {
            takeQuadSnapshot();
        }});
        Ui.click(exitBtn, new Runnable() { @Override public void run() { exitApp(); }});
        TextView[] rowBtns = {menuBtn, recBtn, photoBtn, exitBtn};
        for (int i = 0; i < rowBtns.length; i++) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    0, Ui.dp(this, 64), 1f);
            if (i > 0) blp.leftMargin = Ui.dp(this, 8);
            btnRow.addView(rowBtns[i], blp);
        }
        p.addView(btnRow, Ui.lw());

        // 2×2 四宫格预览：0=前 1=后 2=左 3=右，计时胶囊悬浮右上角
        FrameLayout gridWrap = new FrameLayout(this);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        String[] cellNames = {"前", "后", "左", "右"};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 2; c++) {
                int pos = r * 2 + c;
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (c > 0) clp.leftMargin = Ui.dp(this, 6);
                row.addView(quadCell(pos, cellNames[pos], true), clp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            if (r > 0) rlp.topMargin = Ui.dp(this, 6);
            grid.addView(row, rlp);
        }
        gridWrap.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        quadTime = Ui.text(this, 16, 0xFFFFFFFF, Typeface.BOLD, 1);
        quadTime.setText("00:00");
        quadTime.setBackground(Ui.darkBg(this, 0x80000000, 8));
        quadTime.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        quadTime.setVisibility(View.GONE);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        tlp.topMargin = Ui.dp(this, 8);
        tlp.rightMargin = Ui.dp(this, 8);
        gridWrap.addView(quadTime, tlp);

        // ☰ 菜单：整屏页面（铺满含底栏），首次点 ☰ 时挂到根内容层
        menuPanel = buildEvcamMenuPanel();
        menuPanel.setVisibility(View.GONE);

        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        glp.topMargin = Ui.dp(this, 10);
        p.addView(gridWrap, glp);
        return p;
    }

    /** 原版四路预览格：TextureView 铺满 + 圆角黑底汉字标签（前/后=左上角，左/右=左下角）。
     *  预览直接走盲区同款 Surround 直开 Camera2（用户要求与盲区同一调用方式）。 */
    private FrameLayout quadCell(int pos, String label, boolean labelTop) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(Ui.darkBg(this, Ui.D_BG, 8));
        CamStream cs = camStream(QUAD_CAMS[pos], 4);
        quadStreams[pos] = cs;
        TextureView tv = cs.tv;
        quadViews[pos] = tv;
        f.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView t = Ui.text(this, 16, 0xFFFFFFFF, Typeface.BOLD, 1);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.darkBg(this, 0x80000000, 8));
        int s = Ui.dp(this, 40);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s,
                (labelTop ? Gravity.TOP : Gravity.BOTTOM) | Gravity.START);
        lp.leftMargin = Ui.dp(this, 10);
        if (labelTop) lp.topMargin = Ui.dp(this, 6);
        else lp.bottomMargin = Ui.dp(this, 6);
        f.addView(t, lp);
        return f;
    }

    /** ☰ 展开的功能入口（原版在侧边抽屉）：整屏页面，铺满显示 */
    private LinearLayout buildEvcamMenuPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 16));
        panel.setBackground(Ui.darkBg(this, 0x33151A24, 12));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView btnBack = Ui.darkButton(this, "← 返回", 14, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(btnBack, new Runnable() { @Override public void run() {
            if (menuPanel != null) {
                menuPanel.setVisibility(View.GONE);
                setPagerShown(true);
            }
        }});
        bar.addView(btnBack, Ui.ww());
        bar.addView(hsp(10));
        TextView headTitle = Ui.text(this, 16, Ui.D_TEXT, Typeface.BOLD, 1);
        headTitle.setText("记录仪功能");
        bar.addView(headTitle, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams barLp = Ui.lw();
        barLp.bottomMargin = Ui.dp(this, 12);
        panel.addView(bar, barLp);

        String[] names = {"软件设置", "补盲选项", "超视模式",
                "远程查看", "心跳推图", "视频回放",
                "电报远程", "飞书远程", "照片回放"};
        String[] glyphs = {"⚙", "◐", "⊙", "⇄", "♥", "▶", "✈", "✉", "▦"};
        Runnable[] acts = {
                () -> showEvcamFragment(new com.kooo.evcam.SettingsFragment(), "软件设置"),
                () -> showEvcamFragment(new com.kooo.evcam.BlindSpotSettingsFragment(), "补盲选项"),
                () -> toggleSupervisionMode(),
                () -> showEvcamFragment(new com.kooo.evcam.RemoteViewFragment(), "远程查看"),
                () -> showEvcamFragment(new com.kooo.evcam.heartbeat.HeartbeatFragment(), "心跳推图"),
                () -> showEvcamFragment(new com.kooo.evcam.playback.PlaybackFragmentNew(), "视频回放"),
                () -> showEvcamFragment(new com.kooo.evcam.TelegramFragment(), "电报远程"),
                () -> showEvcamFragment(new com.kooo.evcam.FeishuFragment(), "飞书远程"),
                () -> showEvcamFragment(new com.kooo.evcam.playback.PhotoPlaybackFragmentNew(), "照片回放"),
        };
        for (int r = 0; r < 3; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                final int i = r * 3 + c;
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setBackground(Ui.darkBg(this, 0x33222836, 14));
                TextView g = Ui.text(this, 34, 0xFF6FA8FF, Typeface.BOLD, 1);
                g.setText(glyphs[i]);
                g.setGravity(Gravity.CENTER);
                cell.addView(g);
                TextView n = Ui.text(this, 15, Ui.D_TEXT, Typeface.BOLD, 1);
                n.setText(names[i]);
                n.setGravity(Gravity.CENTER);
                n.setPadding(0, Ui.dp(this, 8), 0, 0);
                cell.addView(n);
                Ui.click(cell, new Runnable() {
                    @Override public void run() {
                        menuPanel.setVisibility(View.GONE);
                        setPagerShown(true);
                        acts[i].run();
                    }
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (c > 0) clp.leftMargin = Ui.dp(this, 10);
                row.addView(cell, clp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            if (r > 0) rlp.topMargin = Ui.dp(this, 10);
            panel.addView(row, rlp);
        }
        return panel;
    }

    private void updateRecordUi() {
        if (recBtn == null) {
            return;
        }
        if (quadRecording) {
            recBtn.setText("■");
            recBtn.setTextColor(0xFFFFFFFF);
            recBtn.setBackground(Ui.darkBg(this, Ui.D_DANGER, 10));
        } else {
            recBtn.setText("○");
            recBtn.setTextColor(0xFFFF5252);
            recBtn.setBackground(Ui.darkBg(this, Ui.D_BTN, 10));
        }
        if (quadTime != null) {
            // 「录制状态显示」开关控制计时角标（此前该开关无任何功能层消费）
            quadTime.setVisibility(quadRecording && appConfig.isRecordingStatsEnabled()
                    ? View.VISIBLE : View.GONE);
        }
    }

    private LinearLayout.LayoutParams mpTop(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, topDp);
        return lp;
    }


    /** 摄像头 id：车机 0=DMS 1=OMS 2=DVR 4=前 5=右 6=左 7=后；无 4-7 时退回前/后各取第一个。 */
    private String[] pickQuadIds() {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            java.util.Set<String> have = new java.util.HashSet<>();
            Collections.addAll(have, cm.getCameraIdList());
            if (have.contains("4") && have.contains("7")
                    && have.contains("6") && have.contains("5")) {
                return new String[]{"4", "7", "6", "5"};
            }
            String front = null, back = null;
            for (String id : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT
                        && front == null) {
                    front = id;
                }
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK
                        && back == null) {
                    back = id;
                }
            }
            return new String[]{front, back, null, null};
        } catch (Throwable t) {
            return new String[]{"0", "1", null, null};
        }
    }

    private void ensureMcm() {
        if (mcm != null) {
            return;
        }
        String[] ids = pickQuadIds();
        // EVCam 侧（FGS 10s 修复循环、补盲服务、悬浮窗）都从 CameraManagerHolder 拿实例；
        // 自己单独 new 会两套管理器抢同一批摄像头，是调用不稳定的根源
        mcm = com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
        if (mcm != null && !mcm.isReleased()) {
            // 服务已在后台初始化过：把预览 TextureView 绑上去并重建 session。
            // 只 setTextureView 不 recreateSession 的话，旧 session 里没有预览 Surface，
            // 画面要等点开始录制（录制路径重建 session）才出来。
            String[] pos = {"front", "back", "left", "right"};
            for (int i = 0; i < pos.length; i++) {
                if (quadViews[i] == null) continue;
                com.kooo.evcam.camera.SingleCamera c = mcm.getCamera(pos[i]);
                if (c != null) {
                    c.setTextureView(quadViews[i]);
                    c.recreateSession();
                }
            }
            note(dvrStatus, "四路引擎就绪（复用服务实例）前=" + ids[0] + " 后=" + ids[1]);
        } else {
            mcm = new MultiCameraManager(this);
            mcm.initCameras(ids[0], quadViews[0], ids[1], quadViews[1],
                    ids[2], quadViews[2], ids[3], quadViews[3]);
            com.kooo.evcam.camera.CameraManagerHolder.getInstance().setCameraManager(mcm);
            note(dvrStatus, "四路引擎就绪 前=" + ids[0] + " 后=" + ids[1]
                    + (ids[2] != null ? " 左=" + ids[2] : "")
                    + (ids[3] != null ? " 右=" + ids[3] : ""));
        }
        wireRemoteCallbacks();
    }

    /** 把 MultiCameraManager 的录制回调接到远程指令层：没有这条线，
     *  远程录制的自动停止定时器永不启动，Watchdog 重建后的时间戳也不同步。 */
    private void wireRemoteCallbacks() {
        if (mcm == null || remoteCommandDispatcher == null) return;
        mcm.setFirstDataWrittenCallback(new MultiCameraManager.FirstDataWrittenCallback() {
            @Override public void onFirstDataWritten() {
                remoteCommandDispatcher.onFirstDataWritten();
            }
        });
        mcm.setTimestampUpdateCallback(new MultiCameraManager.TimestampUpdateCallback() {
            @Override public void onTimestampUpdated(String newTimestamp) {
                remoteCommandDispatcher.onTimestampUpdated(newTimestamp);
            }
        });
    }

    // ===== EVCam 片段宿主 API（EVCam 源码通过 instanceof MainActivity 调用） =====
    private com.kooo.evcam.heartbeat.HeartbeatManager heartbeatManager;

    public com.kooo.evcam.heartbeat.HeartbeatManager getHeartbeatManager() {
        ensureMcm();
        if (heartbeatManager == null) {
            heartbeatManager = new com.kooo.evcam.heartbeat.HeartbeatManager(this);
            java.util.List<com.kooo.evcam.camera.SingleCamera> cams = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String p : QUAD_POS) {
                com.kooo.evcam.camera.SingleCamera c = mcm.getCamera(p);
                if (c != null && seen.add(c.getCameraId())) {
                    cams.add(c);
                }
            }
            heartbeatManager.setCameras(cams);
        }
        return heartbeatManager;
    }

    public int getConnectedCameraCount() {
        return mcm != null ? mcm.getConnectedCameraCount() : 0;
    }

    public int getTotalCameraCount() {
        return QUAD_POS.length;
    }

    public void onHeartbeatConfigChanged() {
        if (heartbeatManager != null) {
            heartbeatManager.onConfigChanged();
        }
    }

    public void goToRecordingInterface() {
        enterTab(4);
    }

    public void toggleDrawer() {
    }


    // ==================== EVCam hub：字段与桥接（自 EVCam MainActivity 移植） ====================

    private static final String TAG = "CCMain";
    private static MainActivity instance;
    public static MainActivity getInstance() { return instance; }
    private static final int REQUEST_OVERLAY_PERMISSION = 1001;

    private com.kooo.evcam.AppConfig appConfig;
    private com.kooo.evcam.dingtalk.DingTalkConfig dingTalkConfig;
    private com.kooo.evcam.telegram.TelegramConfig telegramConfig;
    private com.kooo.evcam.feishu.FeishuConfig feishuConfig;
    private com.kooo.evcam.dingtalk.DingTalkApiClient dingTalkApiClient;
    private com.kooo.evcam.dingtalk.DingTalkStreamManager dingTalkStreamManager;
    private com.kooo.evcam.telegram.TelegramApiClient telegramApiClient;
    private com.kooo.evcam.telegram.TelegramBotManager telegramBotManager;
    private com.kooo.evcam.feishu.FeishuApiClient feishuApiClient;
    private com.kooo.evcam.feishu.FeishuBotManager feishuBotManager;
    private com.kooo.evcam.remote.RemoteCommandDispatcher remoteCommandDispatcher;
    private com.kooo.evcam.camera.ImageAdjustManager imageAdjustManager;
    private com.kooo.evcam.ImageAdjustFloatingWindow imageAdjustFloatingWindow;
    private com.kooo.evcam.StorageCleanupManager storageCleanupManager;
    private com.kooo.evcam.FisheyeCorrectionFloatingWindow fisheyeCorrectionFloatingWindow;
    private com.kooo.evcam.PreviewCorrectionFloatingWindow previewCorrectionFloatingWindow;
    private boolean remoteRecording;
    private boolean isInBackground;
    private boolean isPreparingRecording;
    private boolean isRemoteWakeUp;
    /** 熄屏时由本类设置过修复抑制，唤醒时只清除自己设置的（避免覆盖后台 closeAllCameras 的抑制）。 */
    private boolean sleepRepairSuppressed;
    private long recordingStartTime;
    private int currentSegmentCount;
    private long pendingTelegramChatId;
    private String pendingFeishuChatId;

    // EVCam 录制计时/闪烁 UI 的桥接：ClusterCast 用 startQuad/stopQuad + dvrStatus 提示
    private void startRecording() { if (!quadRecording) startQuad(); }
    private void stopRecording() { if (quadRecording) stopQuad(); }
    private void stopRecordingTimer() { }
    private void stopBlinkAnimation() { }
    private void showPreparingIndicator() { note(dvrStatus, "准备中…"); }
    private void hidePreparingIndicator() {
        if (quadRecording || remoteRecording) note(dvrStatus, "四合一录制中");
    }

    private void returnToBackgroundIfRemoteWakeUp() {
        if (isRemoteWakeUp) {
            isRemoteWakeUp = false;
            moveTaskToBack(true);
        }
    }

    /** 「录制状态显示」开关切换：同步计时角标可见性（此前是空方法，开关切了没反应）。 */
    public void refreshRecordingStatsSettings() {
        if (quadTime != null) {
            quadTime.setVisibility(quadRecording && appConfig != null && appConfig.isRecordingStatsEnabled()
                    ? View.VISIBLE : View.GONE);
        }
    }

    public void refreshPreviewCorrection() { }

    public com.kooo.evcam.camera.ImageAdjustManager getImageAdjustManager() {
        if (imageAdjustManager == null) {
            imageAdjustManager = new com.kooo.evcam.camera.ImageAdjustManager(this);
        }
        return imageAdjustManager;
    }

    private void syncApiClientsFromRemoteServiceManager() {
        if (remoteCommandDispatcher == null) {
            return;
        }
        com.kooo.evcam.RemoteServiceManager serviceManager = com.kooo.evcam.RemoteServiceManager.getInstance();
        com.kooo.evcam.dingtalk.DingTalkApiClient dingTalk = serviceManager.getDingTalkApiClient();
        if (dingTalk != null) {
            remoteCommandDispatcher.setDingTalkApiClient(dingTalk);
            this.dingTalkApiClient = dingTalk;
            this.dingTalkStreamManager = serviceManager.getDingTalkStreamManager();
            AppLog.d(TAG, "从 RemoteServiceManager 同步钉钉 API 客户端");
        }
        com.kooo.evcam.telegram.TelegramApiClient telegram = serviceManager.getTelegramApiClient();
        if (telegram != null) {
            remoteCommandDispatcher.setTelegramApiClient(telegram);
            this.telegramApiClient = telegram;
            this.telegramBotManager = serviceManager.getTelegramBotManager();
            AppLog.d(TAG, "从 RemoteServiceManager 同步 Telegram API 客户端");
        }
        com.kooo.evcam.feishu.FeishuApiClient feishu = serviceManager.getFeishuApiClient();
        if (feishu != null) {
            remoteCommandDispatcher.setFeishuApiClient(feishu);
            this.feishuApiClient = feishu;
            this.feishuBotManager = serviceManager.getFeishuBotManager();
            AppLog.d(TAG, "从 RemoteServiceManager 同步飞书 API 客户端");
        }
    }

    private void updateRemoteViewFragmentUI() {
        for (androidx.fragment.app.Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof com.kooo.evcam.RemoteViewFragment) {
                ((com.kooo.evcam.RemoteViewFragment) f).updateServiceStatus();
            }
        }
    }

    private void updateTelegramFragmentUI() {
        for (androidx.fragment.app.Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof com.kooo.evcam.TelegramFragment) {
                ((com.kooo.evcam.TelegramFragment) f).updateServiceStatus();
            }
        }
    }

    private void updateFeishuFragmentUI() {
        for (androidx.fragment.app.Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof com.kooo.evcam.FeishuFragment) {
                ((com.kooo.evcam.FeishuFragment) f).updateServiceStatus();
            }
        }
    }

    private void exitApp() {
        AppLog.d(TAG, "退出应用");
        // 先退到桌面再后台清理：释放相机/停服务比较耗时，别让点关闭没反应
        finishAffinity();
        runOnUiThread(new Runnable() { @Override public void run() {
            try { if (quadRecording) stopQuad(); } catch (Throwable ignored) { }
        }});
        new Thread(new Runnable() { @Override public void run() { teardown(); } }, "app-exit").start();
    }

    /** 全量清理：投屏/补盲/悬浮窗/MJPEG/心跳/保活链路全部停掉，最后杀进程。 */
    private void teardown() {
        try {
            com.kooo.evcam.CameraForegroundService.sSuppressRestart = true;
            // 四路相机引擎整体释放（预览/录像/取流全停）
            try {
                if (mcm != null) mcm.release();
            } catch (Throwable t) {
                AppLog.e(TAG, "释放相机失败", t);
            }
            mcm = null;
            com.kooo.evcam.CameraForegroundService.stop(this);
            stopService(new Intent(this, CastService.class));
            stopService(new Intent(this, com.kooo.evcam.BlindSpotService.class));
            com.kooo.evcam.FloatingWindowService.stop(this);
            com.kooo.evcam.stream.MjpegStreamManager.stopInstance();
            try {
                if (heartbeatManager != null) heartbeatManager.stop();
            } catch (Throwable ignored) { }
            com.kooo.evcam.KeepAliveReceiver.unregisterTimeTick(this);
            com.kooo.evcam.WakeUpHelper.releasePersistentWakeLock();
            com.kooo.evcam.KeepAliveManager.stopKeepAliveWork(this);
            com.kooo.evcam.RemoteServiceManager.getInstance().stopAllServices();
            dingTalkStreamManager = null;
            dingTalkApiClient = null;
            telegramBotManager = null;
            telegramApiClient = null;
            feishuBotManager = null;
            feishuApiClient = null;
            runOnUiThread(new Runnable() { @Override public void run() {
                stopTailgatePoll();
                DoorGreeting.stop();
                com.jietu.clustercast.SentinelController.shutdown();
            }});
        } catch (Throwable t) {
            AppLog.e(TAG, "清理时出错", t);
        } finally {
            // 保活链路太顽强，直接杀进程确保真的退出（FGS 自动重启已被 sSuppressRestart 抑制）
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    /** 远程/界面重启：走开机同款启动链，1.5 秒后由 TransparentBootActivity 拉起前台服务+远程服务 */
    private void restartApp() {
        AppLog.d(TAG, "应用请求重启，1.5 秒后自动拉起...");
        long trigger = System.currentTimeMillis() + 1500;
        Intent i = new Intent(this, com.kooo.evcam.TransparentBootActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 20001, i,
                android.app.PendingIntent.FLAG_ONE_SHOT | android.app.PendingIntent.FLAG_IMMUTABLE);
        android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
        try {
            am.setExact(android.app.AlarmManager.RTC, trigger, pi);
        } catch (SecurityException e) {
            am.set(android.app.AlarmManager.RTC, trigger, pi);
        }
        exitApp();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (AudioPlayDialog.handleResult(requestCode, data)) return;
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (android.provider.Settings.canDrawOverlays(this)) {
                showImageAdjustFloatingWindow();
            } else {
                Toast.makeText(this, "悬浮窗权限未授予", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        instance = null;
        try {
            unregisterReceiver(remoteReceiver);
        } catch (Throwable ignored) { }
        // 录制会话不跟 Activity 走：Activity 销毁（返回键/重建）时把会话移交
        // 给服务侧 QuadAutoRecord 继续录；真正退出的场景 exitApp/teardown
        // 会先 stopQuad（含停掉自动录像会话）再杀进程
        if (quadRecording) {
            try {
                for (CamStream h : streams) {
                    if (QuadAutoRecord.owns(h.s) || h.page == 4) h.s.keepRecordingOnly();
                }
                if (!quadFromAuto && quad != null) {
                    Surround[] cams = new Surround[4];
                    for (int i = 0; i < 4; i++) {
                        cams[i] = quadStreams[i] != null ? quadStreams[i].s : null;
                    }
                    QuadAutoRecord.adopt(quad, cams);
                }
            } catch (Throwable ignored) { }
            quadRecording = false;
            quadFromAuto = false;
            quad = null;
        }
        super.onDestroy();
    }

// ==== startDingTalkService (from EVCam MainActivity) ====
    public void startDingTalkService() {
        if (!dingTalkConfig.isConfigured()) {
            Toast.makeText(this, "请先配置钉钉参数", Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查本地实例
        if (dingTalkStreamManager != null && dingTalkStreamManager.isRunning()) {
            AppLog.d(TAG, "远程查看服务已在运行（本地实例）");
            return;
        }
        
        // 检查 com.kooo.evcam.RemoteServiceManager 中是否已有实例（防止竞态条件）
        if (com.kooo.evcam.RemoteServiceManager.getInstance().isDingTalkStartingOrRunning()) {
            AppLog.d(TAG, "远程查看服务已在运行（com.kooo.evcam.RemoteServiceManager），获取已有实例");
            dingTalkApiClient = com.kooo.evcam.RemoteServiceManager.getInstance().getDingTalkApiClient();
            dingTalkStreamManager = com.kooo.evcam.RemoteServiceManager.getInstance().getDingTalkStreamManager();
            updateRemoteViewFragmentUI();
            return;
        }

        AppLog.d(TAG, "正在启动远程查看服务...");

        // 创建 API 客户端
        dingTalkApiClient = new com.kooo.evcam.dingtalk.DingTalkApiClient(dingTalkConfig);

        // 创建连接回调
        com.kooo.evcam.dingtalk.DingTalkStreamManager.ConnectionCallback connectionCallback = new com.kooo.evcam.dingtalk.DingTalkStreamManager.ConnectionCallback() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    AppLog.d(TAG, "远程查看服务已连接");
                    Toast.makeText(MainActivity.this, "钉钉远程已启动", Toast.LENGTH_SHORT).show();
                    // 通知 RemoteViewFragment 更新 UI
                    updateRemoteViewFragmentUI();
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    AppLog.d(TAG, "远程查看服务已断开");
                    // 通知 RemoteViewFragment 更新 UI
                    updateRemoteViewFragmentUI();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    AppLog.e(TAG, "远程查看服务连接失败: " + error);
                    Toast.makeText(MainActivity.this, "连接失败: " + error, Toast.LENGTH_LONG).show();
                    // 通知 RemoteViewFragment 更新 UI
                    updateRemoteViewFragmentUI();
                });
            }
        };

        // 更新远程命令分发器的 API 客户端
        if (remoteCommandDispatcher != null) {
            remoteCommandDispatcher.setDingTalkApiClient(dingTalkApiClient);
        }

        // 创建指令回调（使用远程命令分发器）
        com.kooo.evcam.dingtalk.DingTalkStreamManager.CommandCallback commandCallback = new com.kooo.evcam.dingtalk.DingTalkStreamManager.CommandCallback() {
            @Override
            public void onRecordCommand(String conversationId, String conversationType, String userId, int durationSeconds) {
                // 使用分发器处理远程录制
                if (remoteCommandDispatcher != null) {
                    remoteCommandDispatcher.startDingTalkRecording(conversationId, conversationType, userId, durationSeconds);
                }
            }

            @Override
            public void onPhotoCommand(String conversationId, String conversationType, String userId) {
                // 使用分发器处理远程拍照
                if (remoteCommandDispatcher != null) {
                    remoteCommandDispatcher.startDingTalkPhoto(conversationId, conversationType, userId);
                }
            }

            @Override
            public String getStatusInfo() {
                return buildStatusInfo();
            }

            @Override
            public String onStartRecordingCommand() {
                return handleStartRecordingCommand();
            }

            @Override
            public String onStopRecordingCommand() {
                return handleStopRecordingCommand();
            }

            @Override
            public String onExitCommand(boolean confirmed) {
                return handleExitCommand(confirmed);
            }

            @Override
            public String onRestartCommand() {
                return handleRestartCommand();
            }

            @Override
            public String onForegroundCommand() {
                return handleForegroundCommand();
            }

            @Override
            public String onBackgroundCommand() {
                return handleBackgroundCommand();
            }
        };

        // 创建并启动 Stream 管理器（启用自动重连）
        dingTalkStreamManager = new com.kooo.evcam.dingtalk.DingTalkStreamManager(this, dingTalkConfig, dingTalkApiClient, connectionCallback);
        dingTalkStreamManager.start(commandCallback, true); // 启用自动重连
        
        // 注册到 com.kooo.evcam.RemoteServiceManager（确保 Activity 被回收后服务仍可运行）
        com.kooo.evcam.RemoteServiceManager.getInstance().setDingTalkService(dingTalkStreamManager, dingTalkApiClient);
    }

// ==== stopDingTalkService (from EVCam MainActivity) ====
    public void stopDingTalkService() {
        if (dingTalkStreamManager != null) {
            AppLog.d(TAG, "正在停止远程查看服务...");
            dingTalkStreamManager.stop();
            dingTalkStreamManager = null;
            dingTalkApiClient = null;
            
            // 从 com.kooo.evcam.RemoteServiceManager 清除
            com.kooo.evcam.RemoteServiceManager.getInstance().clearDingTalkService();
            
            Toast.makeText(this, "远程查看服务已停止", Toast.LENGTH_SHORT).show();
            // 通知 RemoteViewFragment 更新 UI
            updateRemoteViewFragmentUI();
        }
    }

// ==== isDingTalkServiceRunning (from EVCam MainActivity) ====
    public boolean isDingTalkServiceRunning() {
        return dingTalkStreamManager != null && dingTalkStreamManager.isRunning();
    }

// ==== startTelegramService (from EVCam MainActivity) ====
    public void startTelegramService() {
        if (!telegramConfig.isConfigured()) {
            Toast.makeText(this, "请先配置 Telegram Bot Token", Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查本地实例
        if (telegramBotManager != null && telegramBotManager.isRunning()) {
            AppLog.d(TAG, "Telegram 服务已在运行（本地实例）");
            return;
        }
        
        // 检查 com.kooo.evcam.RemoteServiceManager 中是否已有实例（防止竞态条件）
        if (com.kooo.evcam.RemoteServiceManager.getInstance().isTelegramStartingOrRunning()) {
            AppLog.d(TAG, "Telegram 服务已在运行（com.kooo.evcam.RemoteServiceManager），获取已有实例");
            telegramApiClient = com.kooo.evcam.RemoteServiceManager.getInstance().getTelegramApiClient();
            telegramBotManager = com.kooo.evcam.RemoteServiceManager.getInstance().getTelegramBotManager();
            updateTelegramFragmentUI();
            return;
        }

        AppLog.d(TAG, "正在启动 Telegram 服务...");

        // 创建 API 客户端
        telegramApiClient = new com.kooo.evcam.telegram.TelegramApiClient(telegramConfig);

        // 更新远程命令分发器的 API 客户端
        if (remoteCommandDispatcher != null) {
            remoteCommandDispatcher.setTelegramApiClient(telegramApiClient);
        }

        // 创建连接回调
        com.kooo.evcam.telegram.TelegramBotManager.ConnectionCallback connectionCallback = new com.kooo.evcam.telegram.TelegramBotManager.ConnectionCallback() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    AppLog.d(TAG, "Telegram 服务已连接");
                    Toast.makeText(MainActivity.this, "Telegram 已连接", Toast.LENGTH_SHORT).show();
                    updateTelegramFragmentUI();
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    AppLog.d(TAG, "Telegram 服务已断开");
                    updateTelegramFragmentUI();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    AppLog.e(TAG, "Telegram 服务连接失败: " + error);
                    Toast.makeText(MainActivity.this, "Telegram 连接失败: " + error, Toast.LENGTH_LONG).show();
                    updateTelegramFragmentUI();
                });
            }
        };

        // 创建指令回调（使用远程命令分发器）
        com.kooo.evcam.telegram.TelegramBotManager.CommandCallback commandCallback = new com.kooo.evcam.telegram.TelegramBotManager.CommandCallback() {
            @Override
            public void onRecordCommand(long chatId, int durationSeconds) {
                pendingTelegramChatId = chatId;
                // 使用分发器处理远程录制
                if (remoteCommandDispatcher != null) {
                    remoteCommandDispatcher.startTelegramRecording(chatId, durationSeconds);
                }
            }

            @Override
            public void onPhotoCommand(long chatId) {
                pendingTelegramChatId = chatId;
                // 使用分发器处理远程拍照
                if (remoteCommandDispatcher != null) {
                    remoteCommandDispatcher.startTelegramPhoto(chatId);
                }
            }

            @Override
            public String getStatusInfo() {
                return buildStatusInfo();
            }

            @Override
            public String onStartRecordingCommand() {
                return handleStartRecordingCommand();
            }

            @Override
            public String onStopRecordingCommand() {
                return handleStopRecordingCommand();
            }

            @Override
            public String onExitCommand(boolean confirmed) {
                return handleExitCommand(confirmed);
            }

            @Override
            public String onRestartCommand() {
                return handleRestartCommand();
            }

            @Override
            public String onForegroundCommand() {
                return handleForegroundCommand();
            }

            @Override
            public String onBackgroundCommand() {
                return handleBackgroundCommand();
            }
        };

        // 创建并启动 Bot 管理器
        telegramBotManager = new com.kooo.evcam.telegram.TelegramBotManager(this, telegramConfig, telegramApiClient, connectionCallback);
        telegramBotManager.start(commandCallback);
        
        // 注册到 com.kooo.evcam.RemoteServiceManager（确保 Activity 被回收后服务仍可运行）
        com.kooo.evcam.RemoteServiceManager.getInstance().setTelegramService(telegramBotManager, telegramApiClient);
    }

// ==== stopTelegramService (from EVCam MainActivity) ====
    public void stopTelegramService() {
        if (telegramBotManager != null) {
            AppLog.d(TAG, "正在停止 Telegram 服务...");
            telegramBotManager.stop();
            telegramBotManager = null;
            telegramApiClient = null;
            
            // 从 com.kooo.evcam.RemoteServiceManager 清除
            com.kooo.evcam.RemoteServiceManager.getInstance().clearTelegramService();
            
            Toast.makeText(this, "Telegram 服务已停止", Toast.LENGTH_SHORT).show();
            updateTelegramFragmentUI();
        }
    }

// ==== isTelegramServiceRunning (from EVCam MainActivity) ====
    public boolean isTelegramServiceRunning() {
        return telegramBotManager != null && telegramBotManager.isRunning();
    }

// ==== startFeishuService (from EVCam MainActivity) ====
    public void startFeishuService() {
        if (!feishuConfig.isConfigured()) {
            Toast.makeText(this, "请先配置飞书 App ID 和 App Secret", Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查本地实例
        if (feishuBotManager != null && feishuBotManager.isRunning()) {
            AppLog.d(TAG, "飞书服务已在运行（本地实例）");
            return;
        }
        
        // 检查 com.kooo.evcam.RemoteServiceManager 中是否已有实例
        if (com.kooo.evcam.RemoteServiceManager.getInstance().isFeishuStartingOrRunning()) {
            AppLog.d(TAG, "飞书服务已在运行（com.kooo.evcam.RemoteServiceManager），获取已有实例");
            feishuApiClient = com.kooo.evcam.RemoteServiceManager.getInstance().getFeishuApiClient();
            feishuBotManager = com.kooo.evcam.RemoteServiceManager.getInstance().getFeishuBotManager();
            updateFeishuFragmentUI();
            return;
        }

        AppLog.d(TAG, "正在启动飞书服务...");

        // 创建 API 客户端
        feishuApiClient = new com.kooo.evcam.feishu.FeishuApiClient(feishuConfig);

        // 更新远程命令分发器的 API 客户端
        if (remoteCommandDispatcher != null) {
            remoteCommandDispatcher.setFeishuApiClient(feishuApiClient);
        }

        // 创建连接回调
        com.kooo.evcam.feishu.FeishuBotManager.ConnectionCallback connectionCallback = 
            new com.kooo.evcam.feishu.FeishuBotManager.ConnectionCallback() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    AppLog.d(TAG, "飞书服务已连接");
                    Toast.makeText(MainActivity.this, "飞书已连接", Toast.LENGTH_SHORT).show();
                    updateFeishuFragmentUI();
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    AppLog.d(TAG, "飞书服务已断开");
                    updateFeishuFragmentUI();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    AppLog.e(TAG, "飞书服务连接失败: " + error);
                    Toast.makeText(MainActivity.this, "飞书连接失败: " + error, Toast.LENGTH_LONG).show();
                    updateFeishuFragmentUI();
                });
            }
        };

        // 创建指令回调（使用远程命令分发器）
        com.kooo.evcam.feishu.FeishuBotManager.CommandCallback commandCallback = 
            new com.kooo.evcam.feishu.FeishuBotManager.CommandCallback() {
            @Override
            public void onRecordCommand(String chatId, String messageId, int durationSeconds) {
                pendingFeishuChatId = chatId;
                // 使用分发器处理远程录制
                if (remoteCommandDispatcher != null) {
                    remoteCommandDispatcher.startFeishuRecording(chatId, durationSeconds);
                }
            }

            @Override
            public void onPhotoCommand(String chatId, String messageId) {
                pendingFeishuChatId = chatId;
                // 使用分发器处理远程拍照
                if (remoteCommandDispatcher != null) {
                    remoteCommandDispatcher.startFeishuPhoto(chatId);
                }
            }

            @Override
            public String getStatusInfo() {
                return buildStatusInfo();
            }

            @Override
            public String onStartRecordingCommand() {
                return handleStartRecordingCommand();
            }

            @Override
            public String onStopRecordingCommand() {
                return handleStopRecordingCommand();
            }

            @Override
            public String onExitCommand(boolean confirmed) {
                return handleExitCommand(confirmed);
            }

            @Override
            public String onRestartCommand() {
                return handleRestartCommand();
            }

            @Override
            public String onForegroundCommand() {
                return handleForegroundCommand();
            }

            @Override
            public String onBackgroundCommand() {
                return handleBackgroundCommand();
            }
        };

        // 创建并启动 Bot 管理器
        feishuBotManager = new com.kooo.evcam.feishu.FeishuBotManager(this, feishuConfig, feishuApiClient, connectionCallback);
        feishuBotManager.start(commandCallback);
        
        // 注册到 com.kooo.evcam.RemoteServiceManager
        com.kooo.evcam.RemoteServiceManager.getInstance().setFeishuService(feishuBotManager, feishuApiClient);
    }

// ==== stopFeishuService (from EVCam MainActivity) ====
    public void stopFeishuService() {
        if (feishuBotManager != null) {
            AppLog.d(TAG, "正在停止飞书服务...");
            feishuBotManager.stop();
            feishuBotManager = null;
            feishuApiClient = null;
            
            // 从 com.kooo.evcam.RemoteServiceManager 清除
            com.kooo.evcam.RemoteServiceManager.getInstance().clearFeishuService();
            
            Toast.makeText(this, "飞书服务已停止", Toast.LENGTH_SHORT).show();
            updateFeishuFragmentUI();
        }
    }

// ==== isFeishuServiceRunning (from EVCam MainActivity) ====
    public boolean isFeishuServiceRunning() {
        return feishuBotManager != null && feishuBotManager.isRunning();
    }

// ==== buildStatusInfo (from EVCam MainActivity) ====
    private String buildStatusInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 冥城记录仪 状态\n");
        sb.append("━━━━━━━━━━━━━━\n");
        
        try {
            // 录制状态
            if (quadRecording) {
                sb.append("🎬 录制: 正在录制");
                if (remoteRecording) {
                    sb.append("（远程）");
                }
                sb.append("\n");
                
                // 录制时长
                if (recordingStartTime > 0) {
                    long elapsedMs = System.currentTimeMillis() - recordingStartTime;
                    long totalSeconds = elapsedMs / 1000;
                    long minutes = totalSeconds / 60;
                    long seconds = totalSeconds % 60;
                    sb.append("⏱️ 时长: ").append(String.format("%02d:%02d", minutes, seconds));
                    sb.append(" / 第").append(currentSegmentCount).append("段\n");
                }
            } else {
                sb.append("🎬 录制: 未录制\n");
            }
            
            // 摄像头状态
            if (mcm != null) {
                int connectedCount = mcm.getConnectedCameraCount();
                int totalCount = appConfig.getCameraCount();
                sb.append("📷 摄像头: ").append(connectedCount).append("/").append(totalCount).append(" 已连接\n");
            } else {
                sb.append("📷 摄像头: 未初始化\n");
            }
            
            // 存储信息（简短版）
            try {
                boolean useExternal = appConfig.isUsingExternalSdCard();
                java.io.File storageDir = useExternal ? 
                        StorageHelper.getExternalSdCardRoot(this) : 
                        android.os.Environment.getExternalStorageDirectory();
                if (storageDir != null && storageDir.exists()) {
                    long available = StorageHelper.getAvailableSpace(storageDir);
                    String availableStr = StorageHelper.formatSize(available);
                    sb.append("💾 存储: ").append(useExternal ? "U盘" : "内部");
                    sb.append("（剩余 ").append(availableStr).append("）\n");
                }
            } catch (Exception e) {
                // 忽略存储获取错误
            }
            
            // 应用状态（基于 Activity 生命周期）
            // isInBackground 在 onPause() 时设为 true，onResume() 时设为 false
            // moveTaskToBack() 会触发 onPause()，所以这个判断是准确的
            sb.append("📱 应用: ").append(isInBackground ? "后台" : "前台").append("\n");
            
            // 分隔线
            sb.append("━━━━━━━━━━━━━━\n");
            
            // 设置摘要
            sb.append("⚙️ 设置:\n");
            
            // 自动录制
            sb.append("• 自动录制: ").append(appConfig.isAutoStartRecording() ? "开" : "关");
            if (appConfig.isAutoStartRecording() && appConfig.isScreenOffRecordingEnabled()) {
                sb.append("+息屏");
            }
            sb.append("\n");

            // 开机自动录像
            sb.append("• 开机自动录像: ").append(appConfig.isBootAutoRecord() ? "开" : "关").append("\n");

            // 哨兵模式（停车守卫）
            if (appConfig.isSentinelModeEnabled()) {
                sb.append("• 哨兵模式: 开");
                sb.append(com.jietu.clustercast.SentinelController.isRunning() ? "（值守中" : "（未运行");
                if (appConfig.isSentinelMotionEnabled()) sb.append("/Smart运动检测");
                if (com.jietu.clustercast.SentinelController.isWindowActive()) sb.append("，录像窗口中");
                sb.append("）\n");
            }

            // 防杀加固状态
            sb.append("• 防杀加固: ").append(com.jietu.clustercast.KeepAliveGuard.status(this)).append("\n");

            // 心跳推图
            if (heartbeatManager != null) {
                com.kooo.evcam.heartbeat.HeartbeatConfig hbConfig = heartbeatManager.getConfig();
                if (hbConfig.isEnabled()) {
                    sb.append("• 心跳推图: 开");
                    if (hbConfig.isScreenOnPushEnabled() && hbConfig.isScreenOffPushEnabled()) {
                        sb.append("（亮屏+息屏）");
                    } else if (hbConfig.isScreenOnPushEnabled()) {
                        sb.append("（亮屏）");
                    } else if (hbConfig.isScreenOffPushEnabled()) {
                        sb.append("（息屏）");
                    }
                    sb.append("\n");
                } else {
                    sb.append("• 心跳推图: 关\n");
                }
            }
            
            // 分段时长
            int segmentMin = appConfig.getSegmentDurationMinutes();
            sb.append("• 分段时长: ").append(segmentMin).append("分钟\n");
            
            // 车型
            sb.append("• 车型: ").append(appConfig.getCarModel());
            
        } catch (Exception e) {
            AppLog.e(TAG, "构建状态信息失败", e);
            sb.append("获取状态信息失败: ").append(e.getMessage());
        }
        
        return sb.toString();
    }

// ==== handleStartRecordingCommand (from EVCam MainActivity) ====
    private String handleStartRecordingCommand() {
        AppLog.d(TAG, "处理启动录制指令");

        // 如果已经在录制，返回提示
        if (quadRecording) {
            return "⚠️ 已在录制中，无需重复启动";
        }

        // 直接开四合一录制（原来绕 WakeUpHelper 发 extras 又回到本 Activity，
        // 而 extras 消费端移植时丢了，等于什么都没做）
        ui.post(new Runnable() { @Override public void run() { startRecording(); } });

        return "▶️ 正在启动录制...\n\n发送「状态」查看录制状态\n发送「结束录制」停止录制";
    }

// ==== handleStopRecordingCommand (from EVCam MainActivity) ====
    private String handleStopRecordingCommand() {
        AppLog.d(TAG, "处理结束录制指令");

        // 如果没有在录制，返回提示
        if (!quadRecording) {
            return "⚠️ 当前未在录制";
        }

        // 记录录制时长用于返回信息
        String durationInfo = "";
        if (recordingStartTime > 0) {
            long elapsedMs = System.currentTimeMillis() - recordingStartTime;
            long totalSeconds = elapsedMs / 1000;
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            durationInfo = String.format("，共录制 %02d:%02d", minutes, seconds);
        }

        ui.post(new Runnable() { @Override public void run() { stopQuad(); } });

        return "⏹️ 录制已停止" + durationInfo;
    }

// ==== handleForegroundCommand (from EVCam MainActivity) ====
    private String handleForegroundCommand() {
        AppLog.d(TAG, "处理前台指令");
        
        // 使用 WakeUpHelper 将应用唤醒到前台
        WakeUpHelper.launchForForeground(this);
        
        return "📱 应用已切换到前台";
    }

// ==== handleBackgroundCommand (from EVCam MainActivity) ====
    private String handleBackgroundCommand() {
        AppLog.d(TAG, "处理后台指令");
        
        // 在主线程中执行退到后台
        runOnUiThread(() -> {
            moveTaskToBack(true);
            AppLog.d(TAG, "应用已切换到后台");
        });
        
        return "📴 应用已切换到后台";
    }

// ==== handleExitCommand (from EVCam MainActivity) ====
    private String handleExitCommand(boolean confirmed) {
        AppLog.d(TAG, "处理退出指令，confirmed=" + confirmed);
        
        if (!confirmed) {
            return "⚠️ 确认要退出 冥城记录仪 吗？\n发送「确认退出」执行退出操作。";
        }
        
        // 在主线程中执行退出
        runOnUiThread(() -> {
            AppLog.d(TAG, "执行退出操作...");
            exitApp();
        });
        
        return "👋 冥城记录仪 正在退出...";
    }

// ==== handleRestartCommand (远程重启：杀进程后由 AlarmManager 拉起 TransparentBootActivity) ====
    private String handleRestartCommand() {
        AppLog.d(TAG, "处理重启指令");
        runOnUiThread(() -> {
            AppLog.d(TAG, "执行重启操作...");
            restartApp();
        });
        return "🔄 冥城记录仪 正在重启，稍后自动恢复远程服务...";
    }

// ==== refreshFisheyeCorrection (from EVCam MainActivity) ====
    public void refreshFisheyeCorrection() {
        MultiCameraManager cm = mcm;
        if (cm == null) return;
        String[] positions = {"front", "back", "left", "right"};
        for (String pos : positions) {
            com.kooo.evcam.camera.SingleCamera camera = cm.getCamera(pos);
            if (camera != null) {
                camera.recreateForFisheyeToggle();
            }
        }
    }

// ==== showFisheyeCorrectionFloating (from EVCam MainActivity) ====
    public void showFisheyeCorrectionFloating() {
        if (fisheyeCorrectionFloatingWindow != null && fisheyeCorrectionFloatingWindow.isShowing()) {
            return;
        }
        fisheyeCorrectionFloatingWindow = new com.kooo.evcam.FisheyeCorrectionFloatingWindow(this);
        fisheyeCorrectionFloatingWindow.show();
    }

// ==== showPreviewCorrectionFloating (from EVCam MainActivity) ====
    public void showPreviewCorrectionFloating() {
        if (previewCorrectionFloatingWindow != null && previewCorrectionFloatingWindow.isShowing()) {
            return;
        }
        previewCorrectionFloatingWindow = new com.kooo.evcam.PreviewCorrectionFloatingWindow(this);
        previewCorrectionFloatingWindow.show();
    }

// ==== getCurrentCameraResolutionsInfo (from EVCam MainActivity) ====
    public String getCurrentCameraResolutionsInfo() {
        if (mcm != null) {
            return mcm.getCameraResolutionsInfo();
        }
        return null;
    }

// ==== broadcastCurrentRecordingState (from EVCam MainActivity) ====
    public void broadcastCurrentRecordingState() {
        com.kooo.evcam.FloatingWindowService.sendRecordingStateChanged(this, quadRecording);
    }

// ==== restartStorageCleanupTask (from EVCam MainActivity) ====
    public void restartStorageCleanupTask() {
        if (storageCleanupManager != null) {
            storageCleanupManager.stop();
        }
        storageCleanupManager = new com.kooo.evcam.StorageCleanupManager(this);
        storageCleanupManager.start();
        AppLog.d(TAG, "存储清理任务已重启");
    }

// ==== setImageAdjustEnabled (from EVCam MainActivity) ====
    public void setImageAdjustEnabled(boolean enabled) {
        if (mcm == null) {
            return;
        }
        
        // 设置各摄像头的启用状态
        String[] positions = {"front", "back", "left", "right"};
        for (String position : positions) {
            SingleCamera camera = mcm.getCamera(position);
            if (camera != null) {
                camera.setImageAdjustEnabled(enabled);
            }
        }
        
        // 如果启用，立即应用当前配置的参数
        if (enabled && imageAdjustManager != null) {
            // 延迟执行，确保摄像头会话已经配置好
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                imageAdjustManager.updateAllCameras();
            }, 500);
        }
        
        AppLog.d(TAG, "Image adjust enabled: " + enabled);
    }

// ==== initRemoteCommandDispatcher (from EVCam MainActivity) ====
    private void initRemoteCommandDispatcher() {
        remoteCommandDispatcher = new com.kooo.evcam.remote.RemoteCommandDispatcher(this);
        
        // 设置摄像头控制器
        remoteCommandDispatcher.setCameraController(new RemoteCommandHandler.CameraController() {
            @Override
            public boolean isRecording() {
                return quadRecording || (mcm != null && mcm.isRecording());
            }
            
            @Override
            public boolean hasConnectedCameras() {
                return mcm != null && mcm.hasConnectedCameras();
            }
            
            @Override
            public boolean startRecording(String timestamp) {
                if (mcm != null) {
                    return mcm.startRecording(timestamp);
                }
                return false;
            }
            
            @Override
            public void stopRecording(boolean skipTransfer) {
                if (quadRecording) {
                    // 手动四合一录制走 QuadComposer，mcm.stopRecording 停不掉它
                    ui.post(new Runnable() { @Override public void run() { stopQuad(); } });
                    return;
                }
                if (mcm != null) {
                    mcm.stopRecording(skipTransfer);
                }
            }
            
            @Override
            public void takePicture(String timestamp) {
                if (mcm != null) {
                    mcm.takePicture(timestamp);
                }
            }
            
            @Override
            public void stopRecordingTimer() {
                MainActivity.this.stopRecordingTimer();
            }
            
            @Override
            public void stopBlinkAnimation() {
                MainActivity.this.stopBlinkAnimation();
            }
            
            @Override
            public void startRecording() {
                MainActivity.this.startRecording();
            }
            
            @Override
            public void setSegmentDurationOverride(long durationMs) {
                if (mcm != null) {
                    mcm.setSegmentDurationOverride(durationMs);
                }
            }
            
            @Override
            public void clearSegmentDurationOverride() {
                if (mcm != null) {
                    mcm.clearSegmentDurationOverride();
                }
            }
        });
        
        // 设置录制状态监听器
        remoteCommandDispatcher.setRecordingStateListener(new RemoteCommandHandler.RecordingStateListener() {
            @Override
            public void onRemoteRecordingStart() {
                remoteRecording = true;
            }
            
            @Override
            public void onRemoteRecordingStop() {
                remoteRecording = false;
                isPreparingRecording = false;
                stopBlinkAnimation();
            }
            
            @Override
            public void onPreparing() {
                isPreparingRecording = true;
                showPreparingIndicator();
            }
            
            @Override
            public void onPreparingComplete() {
                isPreparingRecording = false;
                hidePreparingIndicator();
            }
            
            @Override
            public void returnToBackgroundIfRemoteWakeUp() {
                MainActivity.this.returnToBackgroundIfRemoteWakeUp();
            }
            
            @Override
            public boolean isRemoteWakeUp() {
                return MainActivity.this.isRemoteWakeUp;
            }
        });
        
        AppLog.d(TAG, "com.kooo.evcam.remote.RemoteCommandDispatcher 初始化完成");
        
        // 从 com.kooo.evcam.RemoteServiceManager 同步已运行服务的 API 客户端
        // 这确保 Activity 重建后，远程命令处理器能正确使用已有的 API 客户端
        syncApiClientsFromRemoteServiceManager();
    }

// ==== showImageAdjustFloatingWindow (from EVCam MainActivity) ====
    public void showImageAdjustFloatingWindow() {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要悬浮窗权限才能打开调节窗口", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            return;
        }
        
        if (imageAdjustManager == null) {
            Toast.makeText(this, "摄像头未就绪，无法打开调节窗口", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 关闭之前的悬浮窗（如果有）
        if (imageAdjustFloatingWindow != null && imageAdjustFloatingWindow.isShowing()) {
            imageAdjustFloatingWindow.dismiss();
        }
        
        // 创建并显示悬浮窗
        imageAdjustFloatingWindow = new ImageAdjustFloatingWindow(this, imageAdjustManager);
        imageAdjustFloatingWindow.setOnDismissListener(() -> {
            AppLog.d(TAG, "Image adjust floating window dismissed");
        });
        imageAdjustFloatingWindow.show();
        
        AppLog.d(TAG, "Image adjust floating window shown");
    }

// ==== registerCamerasToImageAdjustManager (from EVCam MainActivity) ====
    private void registerCamerasToImageAdjustManager() {
        if (imageAdjustManager == null || mcm == null) {
            return;
        }
        
        // 清空之前注册的摄像头
        imageAdjustManager.clearCameras();
        
        // 注册各位置的摄像头
        String[] positions = {"front", "back", "left", "right"};
        for (String position : positions) {
            SingleCamera camera = mcm.getCamera(position);
            if (camera != null) {
                imageAdjustManager.registerCamera(camera);
            }
        }
        
        // 如果启用了亮度/降噪调节，设置各摄像头的启用状态
        boolean enabled = appConfig.isImageAdjustEnabled();
        if (enabled) {
            setImageAdjustEnabled(true);
        }
        
        AppLog.d(TAG, "Registered cameras to com.kooo.evcam.camera.ImageAdjustManager, adjust enabled: " + enabled);
    }

    private void startQuad() {
        if (quadRecording) {
            return;
        }
        if (mcm != null && mcm.isRecording()) {
            note(dvrStatus, "远程录像进行中，不能再开一路");
            return;
        }
        try {
            // 服务侧自动录像已在录：界面直接接管（复用同一合成器和相机，不断流）
            if (QuadAutoRecord.isActive()) {
                quad = QuadAutoRecord.composer();
                quadFromAuto = true;
                quadRecording = true;
                quadStartMs = android.os.SystemClock.elapsedRealtime();
                if (quadTime != null) quadTime.setText("00:00");
                for (int i = 0; i < 4; i++) {
                    CamStream cs = quadStreams[i];
                    if (cs == null || cs.tex == null) continue;
                    cs.s.start(cs.tex);   // 预览挂回去（录像纹理一直挂着）
                }
                startQuadTicker();
                updateRecordUi();
                syncRecordingFloating();
                note(dvrStatus, "已接管自动录像画面");
                return;
            }
            quadFromAuto = false;
            // 录像前确保前台相机服务在位，否则原车会以 CAMERA_DISABLED 拦开流
            com.kooo.evcam.CameraForegroundService.start(this, "行车记录仪", "四合一录制中");
            File dir = StorageHelper.getVideoDir(this);
            String name = "quad_" + QuadComposer.newSegmentName(dir);
            QuadComposer q = new QuadComposer();
            // 分段时长用设置里选的那档（1/3/5 分钟）——之前主页 ○ 录制不走
            // MultiCameraManager，设置值完全没生效
            q.setSegmentDurationMs(new com.kooo.evcam.AppConfig(this).getSegmentDurationMs());
            q.setCallback(new QuadComposer.Callback() {
                @Override public void onStarted(final String path) {
                    ui.post(new Runnable() {
                        @Override public void run() { note(dvrStatus, "四合一录制中：" + path); }
                    });
                }
                @Override public void onStopped(final String path) {
                    ui.post(new Runnable() {
                        @Override public void run() { note(dvrStatus, "已停止：" + path); }
                    });
                }
                @Override public void onError(final String err) {
                    ui.post(new Runnable() {
                        @Override public void run() { note(dvrStatus, "合成器错误：" + err); }
                    });
                }
            });
            q.start(this, dir.getAbsolutePath(), name, 1920, 1080, 8000000, 30);
            quad = q;
            // 相机侧：把四路预览流挂上录像合成纹理（双输出，预览不断）。
            // 页面不在前台（远程开机录像）就直接以合成纹理开流。
            for (int i = 0; i < 4; i++) {
                CamStream cs = quadStreams[i];
                android.graphics.SurfaceTexture rec = q.getInputTexture(i);
                if (cs == null || rec == null) continue;
                cs.s.setRecordTexture(rec);
                cs.s.start(cs.tex != null ? cs.tex : rec);
            }
            quadRecording = true;
            quadStartMs = android.os.SystemClock.elapsedRealtime();
            if (quadTime != null) {
                quadTime.setText("00:00");
            }
            startQuadTicker();
            updateRecordUi();
            syncRecordingFloating();
        } catch (Throwable t) {
            note(dvrStatus, "启动失败：" + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? " " + t.getMessage() : ""));
        }
    }

    private void startQuadTicker() {
        quadTicker = new Runnable() {
            @Override public void run() {
                if (!quadRecording || quadTime == null) {
                    return;
                }
                if (appConfig == null || !appConfig.isRecordingStatsEnabled()) {
                    quadTime.setVisibility(View.GONE);
                    ui.postDelayed(this, 500);
                    return;
                }
                quadTime.setVisibility(View.VISIBLE);
                long s = (android.os.SystemClock.elapsedRealtime() - quadStartMs) / 1000;
                quadTime.setText(String.format(Locale.US, "%02d:%02d", s / 60, s % 60));
                ui.postDelayed(this, 500);
            }
        };
        ui.post(quadTicker);
    }

    /** 拍照：从四路预览 TextureView 各截当前帧存 JPEG（相机走 Surround 直开，
     *  不再借 MCM 的拍照通道 —— 那会和预览流抢摄像头）。 */
    private void takeQuadSnapshot() {
        File dir = new File(
                android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DCIM), "EVCam_Photo");
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        int saved = 0;
        try {
            dir.mkdirs();
        } catch (Throwable ignored) { }
        for (int i = 0; i < 4; i++) {
            TextureView tv = quadViews == null ? null : quadViews[i];
            if (tv == null || !tv.isAvailable()) continue;
            Bitmap b = tv.getBitmap();
            if (b == null) continue;
            File out = new File(dir, "quad_" + ts + "_" + QUAD_POS[i] + ".jpg");
            FileOutputStream fo = null;
            try {
                fo = new FileOutputStream(out);
                b.compress(Bitmap.CompressFormat.JPEG, 90, fo);
                fo.close();
                fo = null;
                saved++;
                android.media.MediaScannerConnection.scanFile(this,
                        new String[]{out.getAbsolutePath()},
                        new String[]{"image/jpeg"}, null);
            } catch (Throwable t) {
                AppLog.w(TAG, "保存抓拍失败 " + out.getName() + ": " + t);
            } finally {
                if (fo != null) try { fo.close(); } catch (Throwable ignored) { }
                b.recycle();
            }
        }
        note(dvrStatus, saved > 0
                ? "已拍照 " + saved + " 张，保存到 DCIM/EVCam_Photo"
                : "预览未就绪，无法拍照");
    }

    /**
     * 系统熄屏：默认全部相机必须释放——跨休眠持有 Camera2 会话会把 HAL 卡死
     * （2026-10-02 实车：唤醒后我们四路黑、原车倒车也黑，只能重启）。
     * 「息屏录制」开着时例外：前台服务持 PARTIAL_WAKE_LOCK 系统不进休眠（无跨休眠风险），
     * 录像会话继续（服务侧自动录像由 QuadAutoRecord.suspendForSleep 自行判断）。
     */
    public void onSystemSleep() {
        try {
            boolean keepRecording = appConfig != null && appConfig.isScreenOffRecordingEnabled();
            if (quadRecording && !keepRecording) stopQuad();
            if (mcm != null && !keepRecording) {
                if (!QuadAutoRecord.isActive()) {
                    mcm.stopRecording();
                    mcm.pauseAllCamerasByLifecycle();
                }
                // 休眠窗口内一律抑制修复循环：熄屏期间所有"断开"都是主动释放，
                // 此时被修复循环重开会卡死 HAL（2026-10-02 实车根因）。
                // QuadAutoRecord 活跃时它自己走 Surround.stop 释放（无 paused 标志），同样需要抑制。
                mcm.setRepairSuppressed(true);
                sleepRepairSuppressed = true;
            }
            // 已在后台却因 stopQuad 重建了预览流：显式停掉，别让它们跨休眠被闸门重开。
            // 息屏续录时跳过：接管场景 quadStreams 挂着录像合成纹理，停流会让录像黑一路
            if (isInBackground && !keepRecording) {
                for (CamStream h : streams) {
                    try { if (h != null && h.s != null) h.s.stop(); } catch (Throwable ignored) { }
                }
                for (CamStream h : quadStreams) {
                    try { if (h != null && h.s != null) h.s.stop(); } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) { }
    }

    /** 系统亮屏（唤醒）：先解除熄屏期的修复抑制，再让 DVR 引擎相机解除生命周期暂停（预览由 onResume 重建）。 */
    public void onSystemWake() {
        try {
            if (mcm != null) {
                if (sleepRepairSuppressed) {
                    sleepRepairSuppressed = false;
                    mcm.setRepairSuppressed(false);
                }
                mcm.resumeAllCamerasByLifecycle();
            }
        } catch (Throwable ignored) { }
    }

    private void stopQuad() {
        if (!quadRecording) {
            return;
        }
        quadRecording = false;
        quadFromAuto = false;
        boolean wasAuto = QuadAutoRecord.isActive();
        try {
            if (wasAuto) {
                // 界面明确停止：把服务侧会话整个停掉（相机也被它释放），
                // 复用的 Surround 已死，重建自己的预览流
                for (int i = 0; i < 4; i++) {
                    CamStream cs = quadStreams[i];
                    if (cs == null) continue;
                    cs.s = new Surround(this, QUAD_CAMS[i]);
                    cs.s.setReport(new Surround.Report() {
                        @Override public void onStatus(final String msg) {
                            ui.post(new Runnable() {
                                @Override public void run() { note(dvrStatus, msg); }
                            });
                        }
                    });
                }
                QuadAutoRecord.stop(this);
                for (int i = 0; i < 4; i++) {
                    CamStream cs = quadStreams[i];
                    if (cs != null && cs.tex != null) cs.s.start(cs.tex);
                }
            } else {
                for (int i = 0; i < 4; i++) {
                    CamStream cs = quadStreams[i];
                    if (cs == null) continue;
                    cs.s.setRecordTexture(null);
                    if (cs.tex != null) cs.s.start(cs.tex);  // 回到纯预览
                    else cs.s.stop();                         // 页面不在前台就整体停掉
                }
            }
        } catch (Throwable ignored) {
        }
        if (quad != null) {
            quad.stop();
            quad = null;
        }
        if (quadTime != null) {
            quadTime.setText("--:--");
        }
        updateRecordUi();
        syncRecordingFloating();
    }

    /**
     * 录制悬浮按钮同步：按「录制悬浮按钮」开关与录制状态驱动 RecordingFloatingService
     * （此前该开关只写配置，服务无人启动，悬浮按钮永不出现）。
     */
    public void syncRecordingFloating() {
        try {
            Intent i = new Intent(this, com.kooo.evcam.service.RecordingFloatingService.class);
            boolean show = quadRecording && appConfig != null && appConfig.isRecordingFloatingEnabled();
            i.setAction(show ? com.kooo.evcam.service.RecordingFloatingService.ACTION_SHOW
                    : com.kooo.evcam.service.RecordingFloatingService.ACTION_HIDE);
            startService(i);
        } catch (Throwable ignored) {
        }
    }

    /** 按应用名关键词检索已装的启动器应用并在主屏打开，找不到照实说明。 */
    private void launchByHint(TextView status, String[] keys, String what) {
        android.content.pm.PackageManager pm = getPackageManager();
        List<ResolveInfo> rs = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        ResolveInfo hit = null;
        for (ResolveInfo r : rs) {
            if (r.activityInfo.packageName.equals(getPackageName())) continue;
            String lbl = r.loadLabel(pm).toString().toLowerCase(Locale.US);
            for (String k : keys) {
                if (lbl.contains(k)) { hit = r; break; }
            }
            if (hit != null) break;
        }
        if (hit == null) {
            note(status, "在 " + rs.size() + " 个应用里没找到「" + what + "」应用");
            return;
        }
        String name = hit.loadLabel(pm).toString();
        String err = Caster.startOnDisplay(this, hit.activityInfo.packageName,
                hit.activityInfo.name, Caster.MAIN, true);
        note(status, err == null ? "已打开 " + name : "打开 " + name + " 失败：" + err);
    }

    // ---------- 应用列表 ----------

    private List<ResolveInfo> loadApps() {
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> rs = getPackageManager().queryIntentActivities(probe, 0);
        final ArrayList<ResolveInfo> out = new ArrayList<>();
        for (ResolveInfo r : rs)
            if (!r.activityInfo.packageName.equals(getPackageName())) out.add(r);
        final Collator coll = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo x, ResolveInfo y) {
                return coll.compare(x.loadLabel(getPackageManager()).toString(),
                        y.loadLabel(getPackageManager()).toString());
            }
        });
        return out;
    }

    private void chooseApp(ResolveInfo r) {
        android.content.pm.ApplicationInfo ai = r.activityInfo.applicationInfo;
        String name = getPackageManager().getApplicationLabel(ai).toString();
        cfg.setTarget(r.activityInfo.packageName, r.activityInfo.name, name);
        if (cfg.followTop()) cfg.setFollowTop(false);
        adapter.notifyDataSetChanged();
        // 选定后直接开始投屏，无需再点"开始投屏"
        CastService s = CastService.inst();
        if (s != null) {
            s.castNow();
            toast("已选定并投屏：" + name);
        } else {
            toast("已选定：" + name + "（服务启动中，稍后自动投屏）");
        }
    }

    /** 一格：图标在上、名字在下。选中的蓝色高亮。 */
    private class AppAdapter extends BaseAdapter {
        private final List<ResolveInfo> items;
        AppAdapter(List<ResolveInfo> l) { items = l; }
        @Override public int getCount() { return items.size(); }
        @Override public ResolveInfo getItem(int pos) { return items.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout cell;
            if (cv instanceof LinearLayout) cell = (LinearLayout) cv;
            else {
                cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(Ui.dp(MainActivity.this, 4), Ui.dp(MainActivity.this, 6),
                        Ui.dp(MainActivity.this, 4), Ui.dp(MainActivity.this, 6));
                ImageView iv = new ImageView(MainActivity.this);
                cell.addView(iv, new LinearLayout.LayoutParams(
                        Ui.dp(MainActivity.this, 44), Ui.dp(MainActivity.this, 44)));
                TextView t = Ui.text(MainActivity.this, 11, Ui.D_TEXT, Typeface.NORMAL, 1);
                t.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.topMargin = Ui.dp(MainActivity.this, 4);
                cell.addView(t, tlp);
            }
            ResolveInfo r = items.get(pos);
            Drawable icon = r.loadIcon(getPackageManager());
            ((ImageView) cell.getChildAt(0)).setImageDrawable(icon);
            ((TextView) cell.getChildAt(1)).setText(r.loadLabel(getPackageManager()).toString());
            String sel = cfg.pkg();
            boolean selected = sel != null && sel.equals(r.activityInfo.packageName);
            cell.setBackground(Ui.darkBg(MainActivity.this,
                    selected ? Ui.D_BTN_ON : Ui.D_BTN, 10));
            // 卡片刚好放完图标和名称：宽度填满列，高度自适应
            cell.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return cell;
        }
    }

    // ---------- 设置（覆盖应用区；结构克隆左侧栏，那在这台 ROM 上是验证正常的） ----------

    private void showSettingsOverlay() {
        if (inSettings) return;
        // 设置页挂在投屏页右侧面板上，别的页签下点顶栏设置键要先切回投屏页
        if (curTab != 0) switchTab(0);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Ui.darkBg(this, Ui.D_BG, 12));
        page.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16));
        settingsPage = page;

        // ===== 标题条：返回 + 标题（挡位/总线贴片在左侧栏标题栏，见 buildUi） =====
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView btnBack = Ui.darkButton(this, "← 返回", 14, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(btnBack, new Runnable() {
            @Override public void run() { closeSettingsOverlay(); }
        });
        bar.addView(btnBack, Ui.ww());
        bar.addView(hsp(10));
        TextView headTitle = Ui.text(this, 18, Ui.D_TEXT, Typeface.BOLD, 1);
        headTitle.setText("投屏设置");
        bar.addView(headTitle, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(bar, Ui.lw());
        page.addView(vsp(8));

        View divider = new View(this);
        divider.setBackgroundColor(0xFF2A3040);
        page.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
        page.addView(vsp(10));

        // ===== 滚动内容（唯一权重子项，放最后 —— 和左侧栏日志区同构） =====
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        settingsBody = body;

        // 跟随前台（胶囊开关）
        LinearLayout rowF = new LinearLayout(this);
        rowF.setOrientation(LinearLayout.HORIZONTAL);
        rowF.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblFollow = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        lblFollow.setText("跟随前台");
        rowF.addView(lblFollow, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        CapsuleSwitch capFollow = new CapsuleSwitch(MainActivity.this, cfg.followTop());
        capFollow.setOnChange(new CapsuleSwitch.OnChange() {
            @Override public void changed(boolean on) { cfg.setFollowTop(on); }
        });
        rowF.addView(capFollow, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 28)));
        body.addView(rowF, Ui.lw());
        body.addView(vsp(20));

        // 仪表盘悬浮音乐（右侧音乐详情卡片，非投屏也显示）
        LinearLayout rowM = new LinearLayout(this);
        rowM.setOrientation(LinearLayout.HORIZONTAL);
        rowM.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblMusic = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        lblMusic.setText("仪表盘悬浮音乐");
        rowM.addView(lblMusic, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        CapsuleSwitch capMusic = new CapsuleSwitch(MainActivity.this, cfg.clusterMusic());
        capMusic.setOnChange(new CapsuleSwitch.OnChange() {
            @Override public void changed(boolean on) {
                cfg.setClusterMusic(on);
                if (on) ClusterMusicOverlay.show(MainActivity.this);
                else ClusterMusicOverlay.hide();
            }
        });
        rowM.addView(capMusic, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 28)));
        body.addView(rowM, Ui.lw());
        body.addView(vsp(20));

        // 主桌面音乐卡片（launcher 媒体卡片数据代发：云听等源不上报时补位）
        LinearLayout rowCard = new LinearLayout(this);
        rowCard.setOrientation(LinearLayout.HORIZONTAL);
        rowCard.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblCard = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        lblCard.setText("显示原车音乐卡片");
        rowCard.addView(lblCard, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        CapsuleSwitch capCard = new CapsuleSwitch(MainActivity.this, cfg.musicCard());
        capCard.setOnChange(new CapsuleSwitch.OnChange() {
            @Override public void changed(boolean on) {
                cfg.setMusicCard(on);
                CastService s = CastService.inst();
                if (s != null) s.setMusicCard(on);
            }
        });
        rowCard.addView(capCard, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 28)));
        body.addView(rowCard, Ui.lw());
        body.addView(vsp(20));

        // 开门迎宾语设置（子页在应用区内打开，不铺满整屏）
        TextView btnGreet = Ui.darkButton(this, "开门迎宾语设置", 14, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(btnGreet, new Runnable() {
            @Override public void run() { showGreetingPage(); }
        });
        body.addView(btnGreet, Ui.lw());
        body.addView(vsp(20));

        // 投屏自动禁高德：显示授权状态 + 实际执行的命令
        boolean isOwner;
        try {
            isOwner = getSystemService(android.app.admin.DevicePolicyManager.class)
                    .isDeviceOwnerApp(getPackageName());
        } catch (Throwable t) { isOwner = false; }
        TextView lblOwner = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        lblOwner.setText("授权自动禁用地图");
        body.addView(lblOwner, Ui.lw());
        body.addView(vsp(8));
        TextView txOwner = Ui.text(this, 12, isOwner ? 0xFF80FFA0 : Ui.D_TEXT_SUB, Typeface.NORMAL, 4);
        txOwner.setText(isOwner ? "已授权，投屏时自动隐藏原车地图" : "未授权，投屏时无法隐藏原车地图");
        body.addView(txOwner, Ui.lw());
        body.addView(vsp(8));
        TextView txCmd1 = Ui.text(this, 11, Ui.D_TEXT_SUB, Typeface.BOLD, 4);
        txCmd1.setTypeface(Typeface.MONOSPACE);
        // 车载管理模式先放开 DesaySV ROM 的 dpm 限制（残留账号也能授权），
        // 再设 Device Owner。实车验证过的两条命令（2026-10-01）
        txCmd1.setText("adb shell setprop persist.sys.sv.isl true");
        body.addView(txCmd1, Ui.lw());
        body.addView(vsp(8));
        TextView txCmd2 = Ui.text(this, 11, Ui.D_TEXT_SUB, Typeface.BOLD, 4);
        txCmd2.setTypeface(Typeface.MONOSPACE);
        txCmd2.setText("adb shell dpm set-device-owner com.jietu.clustercast/.CastAdminReceiver");
        body.addView(txCmd2, Ui.lw());
        body.addView(vsp(8));
        if (isOwner) {
            TextView btnClearOwner = Ui.darkButton(this, "解除授权", 14, Ui.D_BTN, 0xFFFF8080);
            Ui.click(btnClearOwner, new Runnable() {
                @Override public void run() {
                    try {
                        getSystemService(android.app.admin.DevicePolicyManager.class)
                                .clearDeviceOwnerApp(getPackageName());
                        Toast.makeText(MainActivity.this, "已解除授权", Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        Toast.makeText(MainActivity.this, "解除失败：" + t, Toast.LENGTH_SHORT).show();
                    }
                }
            });
            body.addView(btnClearOwner, Ui.lw());
        }
        body.addView(vsp(20));

        // 权限提示（只显示缺了什么，一行一条）
        boolean topGranted = TopApp.granted(this);
        boolean overlayOk;
        try { overlayOk = Settings.canDrawOverlays(this); } catch (Throwable t) { overlayOk = true; }
        if (!topGranted || !overlayOk) {
            TextView lblPerm = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
            lblPerm.setText("权限");
            body.addView(lblPerm, Ui.lw());
            body.addView(vsp(8));
            if (!topGranted) {
                TextView tp = Ui.text(this, 12, 0xFFFF8080, Typeface.NORMAL, 4);
                tp.setText("「跟随前台」缺使用情况访问权限");
                body.addView(tp, Ui.lw());
                body.addView(vsp(8));
            }
            if (!overlayOk) {
                TextView op = Ui.text(this, 12, 0xFFFF8080, Typeface.NORMAL, 4);
                op.setText("悬浮模式缺「显示在其他应用上层」权限");
                body.addView(op, Ui.lw());
            }
        }

        sv.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        settingsSv = sv;

        settingsView = page;
        rightPanel.removeAllViews();
        rightPanel.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        inSettings = true;
    }

    /** 总线/挡位轮询：VDBus 读写是同步 binder，全程子线程，1 秒一拍。
     *  挡位权威源是 327684/26（1P 2R 3N 4D，见协议 §6），总线不通时退回 android.car。 */
    private void startBusCheck() {
        new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    Vd v = Vd.connect(MainActivity.this);
                    final boolean ok = v.ok();
                    int gear = ok ? v.getGear() : -1;
                    final String g = (gear >= 1 && gear <= 4)
                            ? String.valueOf("PRND".charAt(gear - 1)) : Gear.get(MainActivity.this);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (busView != null) {
                                busView.setText(ok ? "总线已连" : "总线未连");
                                busView.setTextColor(ok ? Ui.GREEN : 0xFFFF8080);
                            }
                            if (gearView != null) {
                                String txt = g != null ? "挡位 " + g : "挡位 --";
                                if (!txt.equals(lastGearText)) {
                                    lastGearText = txt;
                                    gearView.setText(txt);
                                    gearView.setTextColor(g != null ? Ui.D_TEXT : Ui.D_TEXT_SUB);
                                }
                            }
                        }
                    });
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                }
            }
        }, "bus-poll").start();
    }

    private void closeSettingsOverlay() {
        if (!inSettings) return;
        rightPanel.removeAllViews();
        rightPanel.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        settingsView = null;
        settingsPage = null;
        settingsSv = null;
        settingsBody = null;
        inSettings = false;
    }

    // ---------- 开门迎宾语（车门开/关事件 → 内置 TTS 或自定义 MP3，监听在 DoorGreeting） ----------

    private TextView greetStatus;

    private void showGreetingPage() {
        final Cfg cfg = new Cfg(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Ui.darkBg(this, Ui.D_BG, 12));
        page.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView btnBack = Ui.darkButton(this, "← 返回", 14, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(btnBack, new Runnable() {
            @Override public void run() {
                closeSettingsOverlay();
                showSettingsOverlay();
            }
        });
        bar.addView(btnBack, Ui.ww());
        bar.addView(hsp(10));
        TextView title = Ui.text(this, 18, Ui.D_TEXT, Typeface.BOLD, 1);
        title.setText("开门迎宾语设置");
        bar.addView(title, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(bar, Ui.lw());
        page.addView(vsp(12));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        // 总开关：开车内监听线程
        LinearLayout rowMaster = new LinearLayout(this);
        rowMaster.setOrientation(LinearLayout.HORIZONTAL);
        rowMaster.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblMaster = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        lblMaster.setText("启用开门迎宾语");
        rowMaster.addView(lblMaster, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        CapsuleSwitch capMaster = new CapsuleSwitch(this, cfg.greeting());
        capMaster.setOnChange(new CapsuleSwitch.OnChange() {
            @Override public void changed(boolean on) {
                cfg.setGreeting(on);
                if (on) {
                    DoorGreeting.start(MainActivity.this);
                    greetNote("迎宾语监听已启动 ✓", true);
                } else {
                    DoorGreeting.stop();
                    greetNote("迎宾语监听已停止", false);
                }
            }
        });
        rowMaster.addView(capMaster, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 28)));
        body.addView(rowMaster, Ui.lw());

        // 10 个事件：一排两个（主驾开 | 主驾关 同排），选择音频 + 试播
        TextView lblEvents = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        lblEvents.setText("开门/关门播报");
        body.addView(lblEvents, Ui.lw());
        body.addView(vsp(6));
        for (int row = 0; row < 5; row++) {
            // ev 奇数=开，偶数=关：同排左边开、右边关（如 主驾开 | 主驾关）
            final int evOpen = row * 2 + 1;
            final int evClose = row * 2;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(eventCell(evOpen, cfg), Ui.weighted(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
            r.addView(hsp(8));
            r.addView(eventCell(evClose, cfg), Ui.weighted(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
            body.addView(r, Ui.lw());
            body.addView(vsp(6));
        }
        body.addView(vsp(10));

        // 挡位播报：P/R/N/D 一排两个，选择音频 + 试播
        TextView lblGear = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        lblGear.setText("挡位播报");
        body.addView(lblGear, Ui.lw());
        body.addView(vsp(6));
        int[] gears = {1, 2, 3, 4};
        for (int row = 0; row < 2; row++) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(gearCell(gears[row * 2], cfg), Ui.weighted(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
            r.addView(hsp(8));
            r.addView(gearCell(gears[row * 2 + 1], cfg), Ui.weighted(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
            body.addView(r, Ui.lw());
            body.addView(vsp(6));
        }
        body.addView(vsp(10));

        // 发动机播报：启动 | 熄火 一排两个（327684/38）
        TextView lblEngine = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        lblEngine.setText("发动机播报");
        body.addView(lblEngine, Ui.lw());
        body.addView(vsp(6));
        LinearLayout engRow = new LinearLayout(this);
        engRow.setOrientation(LinearLayout.HORIZONTAL);
        engRow.setGravity(Gravity.CENTER_VERTICAL);
        engRow.addView(engineCell(true, cfg), Ui.weighted(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        engRow.addView(hsp(8));
        engRow.addView(engineCell(false, cfg), Ui.weighted(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(engRow, Ui.lw());
        body.addView(vsp(10));

        greetStatus = Ui.text(this, 13, Ui.D_TEXT_SUB, Typeface.NORMAL, 8);
        body.addView(greetStatus, Ui.lw());

        sv.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rightPanel.removeAllViews();
        rightPanel.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 播报格子（车门事件）：名称 + 选择音频 + 试播。 */
    private LinearLayout eventCell(final int ev, final Cfg cfgF) {
        return audioCell(DoorGreeting.eventLabel(ev), String.valueOf(ev), cfgF,
                new Runnable() { @Override public void run() {
                    DoorGreeting.preview(MainActivity.this, ev);
                }});
    }

    /** 挡位播报格子：P/R/N/D（P/R/D 有内置语音，N 没配就静默）。 */
    private LinearLayout gearCell(final int gear, final Cfg cfgF) {
        return audioCell(DoorGreeting.gearLabel(gear), "gear_" + gear, cfgF,
                new Runnable() { @Override public void run() {
                    DoorGreeting.previewGear(MainActivity.this, gear);
                }});
    }

    /** 发动机播报格子：启动/熄火（327684/38，内置 greet_engine_start/stop）。 */
    private LinearLayout engineCell(final boolean start, final Cfg cfgF) {
        return audioCell(DoorGreeting.engineLabel(start),
                start ? "engine_start" : "engine_stop", cfgF,
                new Runnable() { @Override public void run() {
                    DoorGreeting.previewEngine(MainActivity.this, start);
                }});
    }

    private LinearLayout audioCell(String label, final String storeKey, final Cfg cfgF,
            final Runnable onPreview) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setBackground(Ui.darkBg(this, Ui.D_CARD, 10));
        int cpad = Ui.dp(this, 8);
        cell.setPadding(cpad, cpad, cpad, cpad);
        TextView lbl = Ui.text(this, 13, Ui.D_TEXT, Typeface.BOLD, 1);
        lbl.setText(label);
        cell.addView(lbl, Ui.lw());
        cell.addView(vsp(4));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final GreetAudio.Cfg init = GreetAudio.parse(cfgF.audio(storeKey));
        final boolean initHas = init != null && init.src != null && !init.src.isEmpty();
        final TextView chip = Ui.darkButton(this, "选择音频：" + GreetAudio.labelOf(init), 12,
                initHas ? Ui.D_GREEN : Ui.D_BTN,
                initHas ? 0xFF102418 : Ui.D_TEXT);
        Ui.click(chip, new Runnable() { @Override public void run() {
            new AudioPlayDialog(MainActivity.this, storeKey, label,
                    GreetAudio.parse(cfgF.audio(storeKey)), new Runnable() {
                @Override public void run() {
                    GreetAudio.Cfg now = GreetAudio.parse(cfgF.audio(storeKey));
                    boolean has = now != null && now.src != null && !now.src.isEmpty();
                    chip.setText("选择音频：" + GreetAudio.labelOf(now));
                    chip.setBackground(Ui.darkBg(MainActivity.this, has ? Ui.D_GREEN : Ui.D_BTN, 20));
                    chip.setTextColor(has ? 0xFF102418 : Ui.D_TEXT);
                    greetNote(label + " 音频已保存", true);
                }
            }).show();
        }});
        row.addView(chip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(hsp(6));
        TextView btnPlay = Ui.darkButton(this, "试播", 12, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(btnPlay, new Runnable() { @Override public void run() {
            onPreview.run();
            greetNote("试播 " + label, true);
        }});
        row.addView(btnPlay, Ui.ww());
        cell.addView(row, Ui.lw());
        return cell;
    }

    void greetNote(String s, boolean ok) {
        if (greetStatus == null) return;
        greetStatus.setText(s);
        greetStatus.setTextColor(ok ? Ui.GREEN : 0xFFFF8080);
    }

    // ---------- 辅助 ----------

    private View vsp(int dpH) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, dpH)));
        return v;
    }

    private View hsp(int dpW) {
        View v = new View(this);
        // 高度必须固定：普通 View 的 WRAP 高度在 AT_MOST 测量下会取整个可用高度
        // （View.getDefaultSize 对 AT_MOST 直接返回 specSize），把所在行撑到满屏高
        v.setLayoutParams(new LinearLayout.LayoutParams(
                Ui.dp(this, dpW), Ui.dp(this, 1)));
        return v;
    }

    /** 胶囊开关：圆角轨道 + 滑动圆点，开=绿色。尺寸由使用方的 LayoutParams 固定给足。 */
    private static final class CapsuleSwitch extends View {
        interface OnChange { void changed(boolean on); }
        private boolean on;
        private OnChange cb;
        CapsuleSwitch(android.content.Context c, boolean init) {
            super(c);
            on = init;
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    on = !on;
                    invalidate();
                    if (cb != null) cb.changed(on);
                }
            });
        }
        void setOnChange(OnChange c) { cb = c; }
        @Override protected void onDraw(Canvas cv) {
            float r = getHeight() / 2f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(on ? Ui.D_GREEN : 0xFF39414F);
            cv.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, p);
            float m = Ui.dp(getContext(), 3);
            float kd = getHeight() - m * 2;
            float kx = on ? getWidth() - m - kd / 2 : m + kd / 2;
            p.setColor(0xFFFFFFFF);
            cv.drawCircle(kx, getHeight() / 2f, kd / 2f, p);
        }
    }

    // ---------- 刷新 ----------

    private void refresh() {
        CastService s = CastService.inst();
        if (s != null && !sinkBound) {
            s.setSink(this);
            sinkBound = true;
        }
        String lbl = cfg.label();
        String pkg = cfg.pkg();
        String target = lbl != null ? lbl : (pkg != null ? pkg : null);
        if (s != null) {
            String cp = s.castingPkg();
            if (cp != null) {
                tvStatus.setText("正在投屏：" + s.label(cp)
                        + "\n目标：" + (target != null ? target : "未选"));
                tvStatus.setTextColor(Ui.GREEN);
            } else {
                tvStatus.setText("目标：" + (target != null ? target : "未选择（三指左滑投屏）"));
                tvStatus.setTextColor(Ui.D_TEXT_SUB);
            }
        } else {
            tvStatus.setText("服务未启动");
            tvStatus.setTextColor(Ui.D_TEXT_SUB);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override public void onLog(String s) {
        if (tvLog == null) return;
        tvLog.setText(s);
        svLog.post(new Runnable() {
            @Override public void run() { svLog.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
