
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
    private long recordingStartTime;
    private int currentSegmentCount;
    private long pendingTelegramChatId;
    private long pendingFeishuChatId;

    // EVCam 录制计时/闪烁 UI 的桥接：ClusterCast 用 startQuad/stopQuad + dvrStatus 提示
    private void startRecording() { if (!quadRecording) startQuad(); }
    private void stopRecording() { if (quadRecording) stopQuad(); }
    private void stopRecordingTimer() { }
    private void stopBlinkAnimation() { }

    private void returnToBackgroundIfRemoteWakeUp() {
        if (isRemoteWakeUp) {
            isRemoteWakeUp = false;
            moveTaskToBack(true);
        }
    }

    private void refreshRecordingStatsSettings() { }

    private void refreshPreviewCorrection() { }

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
        AppLog.d(TAG, "用户请求退出应用，停止所有服务...");
        if (quadRecording) stopQuad();
        com.kooo.evcam.CameraForegroundService.stop(this);
        com.kooo.evcam.RemoteServiceManager.getInstance().stopAllServices();
        dingTalkStreamManager = null;
        dingTalkApiClient = null;
        telegramBotManager = null;
        telegramApiClient = null;
        finishAffinity();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
        super.onDestroy();
    }

