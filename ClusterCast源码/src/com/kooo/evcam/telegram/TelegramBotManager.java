package com.kooo.evcam.telegram;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Telegram Bot 消息轮询管理器
 * 使用 Long Polling 方式接收消息
 */
public class TelegramBotManager {
    private static final String TAG = "TelegramBotManager";
    private static final int POLL_TIMEOUT = 30; // 长轮询超时时间（秒）
    private static final int POLL_LIMIT = 5; // 每次拉取的消息数量限制
    private static final int MESSAGE_EXPIRE_SECONDS = 600; // 消息过期时间（10分钟 = 600秒）
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 5000; // 5秒
    private static final long CONFLICT_RETRY_DELAY_MS = 10000; // 409 冲突时等待 10 秒再重试
    private static final int MAX_CONFLICT_RETRIES = 3; // 最大冲突重试次数

    private final Context context;
    private final TelegramConfig config;
    private final TelegramApiClient apiClient;
    private final ConnectionCallback connectionCallback;
    private final Handler mainHandler;

    private volatile boolean isRunning = false;
    private volatile boolean shouldStop = false;
    private Thread pollingThread;
    private int reconnectAttempts = 0;
    private int conflictRetries = 0;
    private CommandCallback currentCommandCallback;

    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    public interface CommandCallback {
        void onRecordCommand(long chatId, int durationSeconds);
        void onPhotoCommand(long chatId);
        String getStatusInfo();
        String onStartRecordingCommand();
        String onStopRecordingCommand();
        String onExitCommand(boolean confirmed);

        /**
         * 重启应用（走开机同款启动链）
         * @return 执行结果消息
         */
        default String onRestartCommand() {
            return "功能不可用";
        }
        
        /**
         * 切换到前台
         * @return 执行结果消息
         */
        default String onForegroundCommand() {
            return "功能不可用";
        }
        
        /**
         * 切换到后台
         * @return 执行结果消息
         */
        default String onBackgroundCommand() {
            return "功能不可用";
        }
    }

    public TelegramBotManager(Context context, TelegramConfig config,
                               TelegramApiClient apiClient, ConnectionCallback callback) {
        this.context = context;
        this.config = config;
        this.apiClient = apiClient;
        this.connectionCallback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 启动消息轮询
     */
    public synchronized void start(CommandCallback commandCallback) {
        if (isRunning) {
            AppLog.w(TAG, "Bot 已在运行");
            return;
        }

        // 立即设置为运行状态，防止重复启动
        isRunning = true;
        
        this.currentCommandCallback = commandCallback;
        this.shouldStop = false;
        this.reconnectAttempts = 0;
        this.conflictRetries = 0;

        startPolling();
    }

    /**
     * 内部方法：启动轮询线程
     */
    private void startPolling() {
        pollingThread = new Thread(() -> {
            try {
                AppLog.d(TAG, "正在验证 Bot Token...");

                // 验证 Token
                JsonObject botInfo = apiClient.getMe();
                String botUsername = botInfo.get("username").getAsString();
                AppLog.d(TAG, "Bot 验证成功: @" + botUsername);

                // 清除可能存在的旧连接（发送一个无等待的请求来"抢占"连接）
                AppLog.d(TAG, "清除旧连接状态...");
                try {
                    // 使用 timeout=0 立即返回，这会断开其他可能存在的长轮询连接
                    apiClient.getUpdates(-1, 0, 1);
                    Thread.sleep(500); // 短暂等待
                } catch (Exception e) {
                    AppLog.d(TAG, "清除旧连接: " + e.getMessage());
                    // 如果是 409 错误，等待更长时间
                    if (e.getMessage() != null && e.getMessage().contains("409")) {
                        AppLog.d(TAG, "检测到 409 冲突，等待旧连接断开...");
                        Thread.sleep(3000);
                    }
                }

                // isRunning 已在 start() 中设置
                reconnectAttempts = 0;

                // 通知连接成功
                mainHandler.post(() -> connectionCallback.onConnected());

                // 开始长轮询
                long offset = config.getLastUpdateId() + 1;

                while (!shouldStop) {
                    try {
                        JsonArray updates = apiClient.getUpdates(offset, POLL_TIMEOUT, POLL_LIMIT);
                        long currentTime = System.currentTimeMillis() / 1000; // 当前时间（秒）

                        for (int i = 0; i < updates.size(); i++) {
                            JsonObject update = updates.get(i).getAsJsonObject();
                            long updateId = update.get("update_id").getAsLong();

                            // 处理消息
                            if (update.has("message")) {
                                JsonObject message = update.getAsJsonObject("message");

                                // 检查消息时间，忽略超过 10 分钟的旧消息
                                if (message.has("date")) {
                                    long messageTime = message.get("date").getAsLong();
                                    long messageAge = currentTime - messageTime;

                                    if (messageAge > MESSAGE_EXPIRE_SECONDS) {
                                        AppLog.d(TAG, "忽略过期消息，消息时间: " + messageTime +
                                                ", 已过去 " + messageAge + " 秒");
                                        // 仍然更新 offset，避免重复拉取
                                        offset = updateId + 1;
                                        config.saveLastUpdateId(updateId);
                                        continue;
                                    }
                                }

                                processMessage(message);
                            }

                            // 更新 offset
                            offset = updateId + 1;
                            config.saveLastUpdateId(updateId);
                        }

                    } catch (Exception e) {
                        if (!shouldStop) {
                            String errorMsg = e.getMessage();
                            AppLog.e(TAG, "轮询出错: " + errorMsg);
                            
                            // 检查是否是 409 冲突错误
                            if (errorMsg != null && errorMsg.contains("409")) {
                                conflictRetries++;
                                if (conflictRetries >= MAX_CONFLICT_RETRIES) {
                                    // 只记录日志，不弹窗（不影响实际连接）
                                    AppLog.w(TAG, "409 冲突错误达到最大重试次数，可能有其他设备在运行此 Bot");
                                    shouldStop = true;
                                    break;
                                }
                                AppLog.d(TAG, "409 冲突，等待 " + CONFLICT_RETRY_DELAY_MS + "ms 后重试（第 " + conflictRetries + " 次）");
                                Thread.sleep(CONFLICT_RETRY_DELAY_MS);
                            } else {
                                // 其他错误，短暂休眠后继续
                                conflictRetries = 0; // 重置冲突计数
                                Thread.sleep(1000);
                            }
                        }
                    }
                }

            } catch (Exception e) {
                AppLog.e(TAG, "启动 Bot 失败", e);
                isRunning = false;

                // 尝试重连
                if (!shouldStop && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempts++;
                    AppLog.d(TAG, "将在 " + RECONNECT_DELAY_MS + "ms 后尝试第 " + reconnectAttempts + " 次重连");
                    mainHandler.postDelayed(() -> {
                        if (!shouldStop) {
                            startPolling();
                        }
                    }, RECONNECT_DELAY_MS);
                } else if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                    // 只记录日志，不弹窗
                    AppLog.w(TAG, "达到最大重连次数（" + MAX_RECONNECT_ATTEMPTS + "），启动失败: " + e.getMessage());
                }
            }

            isRunning = false;
            if (shouldStop) {
                mainHandler.post(() -> connectionCallback.onDisconnected());
            }
        });

        pollingThread.setName("TelegramPolling");
        pollingThread.start();
    }

    /**
     * 处理收到的消息
     */
    private void processMessage(JsonObject message) {
        try {
            // 获取 chat 信息
            JsonObject chat = message.getAsJsonObject("chat");
            long chatId = chat.get("id").getAsLong();
            String chatType = chat.get("type").getAsString(); // private, group, supergroup, channel

            // 检查是否允许此 chat
            if (!config.isChatIdAllowed(chatId)) {
                AppLog.d(TAG, "Chat ID 不在白名单中: " + chatId);
                return;
            }

            // 获取消息文本
            if (!message.has("text")) {
                return; // 非文本消息，忽略
            }

            String text = message.get("text").getAsString();
            AppLog.d(TAG, "收到消息 - chatId: " + chatId + ", type: " + chatType + ", text: " + text);

            // 解析指令
            String command = parseCommand(text);
            AppLog.d(TAG, "解析的指令: " + command);

            // 处理指令
            if (command.startsWith("/record") || command.startsWith("录制") ||
                command.toLowerCase().startsWith("record")) {

                int durationSeconds = parseRecordDuration(command);
                AppLog.d(TAG, "收到录制指令，时长: " + durationSeconds + " 秒");

                // 发送确认消息
                String confirmMsg = String.format("收到录制指令，开始录制 %d 秒视频...", durationSeconds);
                sendResponseAndThen(chatId, confirmMsg, () -> {
                    // 使用 WakeUpHelper 唤醒并启动录制
                    AppLog.d(TAG, "使用 WakeUpHelper 启动录制...");
                    WakeUpHelper.launchForRecordingTelegram(context, chatId, durationSeconds);
                });

            } else if ("/photo".equals(command) || "拍照".equals(command) ||
                       "photo".equalsIgnoreCase(command)) {

                AppLog.d(TAG, "收到拍照指令");

                // 发送确认消息
                sendResponseAndThen(chatId, "收到拍照指令，正在拍照...", () -> {
                    // 使用 WakeUpHelper 唤醒并启动拍照
                    AppLog.d(TAG, "使用 WakeUpHelper 启动拍照...");
                    WakeUpHelper.launchForPhotoTelegram(context, chatId);
                });

            } else if ("/status".equals(command) || "状态".equals(command)) {
                // 状态指令：显示应用详细状态
                AppLog.d(TAG, "收到状态指令");
                String statusInfo = currentCommandCallback != null ?
                        currentCommandCallback.getStatusInfo() : "✅ Bot 正在运行中";
                apiClient.sendMessage(chatId, statusInfo);

            } else if ("视频".equals(command) || "最新视频".equals(command) ||
                       "/video".equals(command) || "video".equalsIgnoreCase(command)) {
                // 远程查看视频：把最新一段录像发到当前会话
                AppLog.d(TAG, "收到视频指令");
                final File latest = com.kooo.evcam.remote.upload.MediaFileFinder.findLatestVideoFile(context);
                if (latest == null) {
                    apiClient.sendMessage(chatId, "📭 暂无录像文件，先发送「录制」录一段");
                } else {
                    apiClient.sendMessage(chatId, "📤 正在发送最新录像：\n" + latest.getName());
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                apiClient.sendVideo(chatId, latest, null, 0);
                                AppLog.d(TAG, "视频发送成功: " + latest.getName());
                            } catch (Exception e) {
                                AppLog.e(TAG, "视频发送失败: " + e.getMessage());
                                try {
                                    apiClient.sendMessage(chatId, "❌ 视频发送失败: " + e.getMessage());
                                } catch (Exception ignored) { }
                            }
                        }
                    }).start();
                }

            } else if ("视频列表".equals(command) || "videos".equalsIgnoreCase(command)
                       || "/videos".equals(command)) {
                // 列出最近录像
                AppLog.d(TAG, "收到视频列表指令");
                java.util.List<File> videos = com.kooo.evcam.remote.upload.MediaFileFinder.listLatestVideoFiles(context, 10);
                if (videos.isEmpty()) {
                    apiClient.sendMessage(chatId, "📭 暂无录像文件");
                } else {
                    StringBuilder sb = new StringBuilder("🎬 最近录像（新→旧）：\n");
                    for (int i = 0; i < videos.size(); i++) {
                        sb.append(i + 1).append(". ").append(videos.get(i).getName()).append('\n');
                    }
                    sb.append("发送「视频2」回放第2个");
                    apiClient.sendMessage(chatId, sb.toString());
                }

            } else if (parseTrailingNumber(command, "视频") > 0
                    || parseTrailingNumber(command, "video") > 0
                    || parseTrailingNumber(command, "/video") > 0) {
                // 回放指定序号的录像
                int index = Math.max(Math.max(parseTrailingNumber(command, "视频"),
                        parseTrailingNumber(command, "video")),
                        parseTrailingNumber(command, "/video"));
                java.util.List<File> videos = com.kooo.evcam.remote.upload.MediaFileFinder.listLatestVideoFiles(context, 20);
                if (index > videos.size()) {
                    apiClient.sendMessage(chatId, "❌ 没有第" + index + "个录像，共 " + videos.size() + " 个");
                } else {
                    final File pick = videos.get(index - 1);
                    final long fChatId = chatId;
                    apiClient.sendMessage(chatId, "📤 正在发送录像" + index + "：\n" + pick.getName());
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                apiClient.sendVideo(fChatId, pick, null, 0);
                                AppLog.d(TAG, "视频发送成功: " + pick.getName());
                            } catch (Exception e) {
                                AppLog.e(TAG, "视频发送失败: " + e.getMessage());
                                try {
                                    apiClient.sendMessage(fChatId, "❌ 视频发送失败: " + e.getMessage());
                                } catch (Exception ignored) { }
                            }
                        }
                    }).start();
                }

            } else if ("照片".equals(command) || "最新照片".equals(command)
                       || "/latest_photo".equals(command)) {
                // 远程查看最新照片
                AppLog.d(TAG, "收到照片回放指令");
                final File latestPhoto = com.kooo.evcam.remote.upload.MediaFileFinder.findLatestPhotoFile(context);
                final long fChatId = chatId;
                if (latestPhoto == null) {
                    apiClient.sendMessage(chatId, "📭 暂无照片文件，先发送「拍照」拍一张");
                } else {
                    apiClient.sendMessage(chatId, "📤 正在发送最新照片：\n" + latestPhoto.getName());
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                apiClient.sendPhoto(fChatId, latestPhoto);
                                AppLog.d(TAG, "照片发送成功: " + latestPhoto.getName());
                            } catch (Exception e) {
                                AppLog.e(TAG, "照片发送失败: " + e.getMessage());
                                try {
                                    apiClient.sendMessage(fChatId, "❌ 照片发送失败: " + e.getMessage());
                                } catch (Exception ignored) { }
                            }
                        }
                    }).start();
                }

            } else if ("照片列表".equals(command) || "photos".equalsIgnoreCase(command)
                       || "/photos".equals(command)) {
                // 列出最近照片
                AppLog.d(TAG, "收到照片列表指令");
                java.util.List<File> photos = com.kooo.evcam.remote.upload.MediaFileFinder.listLatestPhotoFiles(context, 10);
                if (photos.isEmpty()) {
                    apiClient.sendMessage(chatId, "📭 暂无照片文件");
                } else {
                    StringBuilder sb = new StringBuilder("🖼 最近照片（新→旧）：\n");
                    for (int i = 0; i < photos.size(); i++) {
                        sb.append(i + 1).append(". ").append(photos.get(i).getName()).append('\n');
                    }
                    sb.append("发送「照片2」回放第2张");
                    apiClient.sendMessage(chatId, sb.toString());
                }

            } else if (parseTrailingNumber(command, "照片") > 0) {
                // 回放指定序号的照片
                int index = parseTrailingNumber(command, "照片");
                java.util.List<File> photos = com.kooo.evcam.remote.upload.MediaFileFinder.listLatestPhotoFiles(context, 20);
                if (index > photos.size()) {
                    apiClient.sendMessage(chatId, "❌ 没有第" + index + "张照片，共 " + photos.size() + " 张");
                } else {
                    final File pick = photos.get(index - 1);
                    final long fChatId = chatId;
                    apiClient.sendMessage(chatId, "📤 正在发送照片" + index + "：\n" + pick.getName());
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                apiClient.sendPhoto(fChatId, pick);
                                AppLog.d(TAG, "照片发送成功: " + pick.getName());
                            } catch (Exception e) {
                                AppLog.e(TAG, "照片发送失败: " + e.getMessage());
                                try {
                                    apiClient.sendMessage(fChatId, "❌ 照片发送失败: " + e.getMessage());
                                } catch (Exception ignored) { }
                            }
                        }
                    }).start();
                }

            } else if ("启动录制".equals(command) || "开始录制".equals(command) || 
                       "/start_rec".equals(command) || "start".equalsIgnoreCase(command)) {
                // 启动录制指令：唤醒到前台并开始持续录制
                AppLog.d(TAG, "收到启动录制指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onStartRecordingCommand();
                    apiClient.sendMessage(chatId, result);
                } else {
                    apiClient.sendMessage(chatId, "❌ 功能不可用");
                }

            } else if ("结束录制".equals(command) || "停止录制".equals(command) || 
                       "/stop_rec".equals(command) || "stop".equalsIgnoreCase(command)) {
                // 结束录制指令：停止录制并退到后台
                AppLog.d(TAG, "收到结束录制指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onStopRecordingCommand();
                    apiClient.sendMessage(chatId, result);
                } else {
                    apiClient.sendMessage(chatId, "❌ 功能不可用");
                }

            } else if ("退出".equals(command) || "/exit".equals(command) || 
                       "exit".equalsIgnoreCase(command)) {
                // 退出指令：需要二次确认
                AppLog.d(TAG, "收到退出指令（需二次确认）");
                apiClient.sendMessage(chatId, 
                    "⚠️ 确认要退出 冥城记录仪 吗？\n\n" +
                    "退出后将停止所有录制和远程服务。\n" +
                    "发送「确认退出」或 /confirm_exit 执行退出操作。");

            } else if ("确认退出".equals(command) || "/confirm_exit".equals(command)) {
                // 确认退出指令：执行退出
                AppLog.d(TAG, "收到确认退出指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onExitCommand(true);
                    apiClient.sendMessage(chatId, result);
                } else {
                    apiClient.sendMessage(chatId, "❌ 功能不可用");
                }

            } else if ("前台".equals(command) || "/foreground".equals(command) ||
                       "foreground".equalsIgnoreCase(command)) {
                // 前台指令：将应用切换到前台
                AppLog.d(TAG, "收到前台指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onForegroundCommand();
                    apiClient.sendMessage(chatId, result);
                } else {
                    apiClient.sendMessage(chatId, "❌ 功能不可用");
                }

            } else if ("重启".equals(command) || "/restart".equals(command) ||
                       "restart".equalsIgnoreCase(command)) {
                // 重启指令：走开机同款启动链
                AppLog.d(TAG, "收到重启指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onRestartCommand();
                    apiClient.sendMessage(chatId, result);
                } else {
                    apiClient.sendMessage(chatId, "❌ 功能不可用");
                }

            } else if ("后台".equals(command) || "/background".equals(command) ||
                       "background".equalsIgnoreCase(command)) {
                // 后台指令：将应用切换到后台
                AppLog.d(TAG, "收到后台指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onBackgroundCommand();
                    apiClient.sendMessage(chatId, result);
                } else {
                    apiClient.sendMessage(chatId, "❌ 功能不可用");
                }

            } else if ("/help".equals(command) || "帮助".equals(command) ||
                       "/start".equals(command)) {

                apiClient.sendMessage(chatId,
                    "📋 <b>冥城记录仪 远程控制</b>\n" +
                    "━━━━━━━━━━━━━━\n\n" +
                    "📹 <b>远程录制</b>\n" +
                    "/record ─ 录制60秒视频\n" +
                    "/record 30 ─ 录制指定秒数\n" +
                    "录制 / 录制30 ─ 中文指令\n\n" +
                    "▶️ <b>持续录制</b>\n" +
                    "/start_rec ─ 开始持续录制\n" +
                    "/stop_rec ─ 停止录制\n" +
                    "启动录制 / 结束录制 ─ 中文\n\n" +
                    "📷 <b>拍照</b>\n" +
                    "/photo ─ 拍摄照片\n" +
                    "拍照 ─ 中文指令\n\n" +
                    "🎬 <b>回放</b>\n" +
                    "视频 ─ 发送最新一段录像\n" +
                    "视频列表 / /videos ─ 列出最近录像\n" +
                    "视频2 / /video2 ─ 回放第2个录像\n" +
                    "照片 / 照片2 ─ 发送最新/第2张照片\n" +
                    "照片列表 / /photos ─ 列出最近照片\n\n" +
                    "🔄 <b>前后台切换</b>\n" +
                    "/foreground ─ 切换到前台\n" +
                    "/background ─ 切换到后台\n" +
                    "前台 / 后台 ─ 中文指令\n\n" +
                    "ℹ️ <b>其他</b>\n" +
                    "/status ─ 查看应用状态\n" +
                    "/exit ─ 退出应用\n" +
                    "/restart ─ 重启应用\n" +
                    "/help ─ 显示此帮助\n\n" +
                    "━━━━━━━━━━━━━━\n" +
                    "💡 所有指令支持中英文");

            } else {
                AppLog.d(TAG, "未识别的指令: " + command);
                apiClient.sendMessage(chatId,
                    "未识别的指令。发送 /help 查看可用指令。");
            }

        } catch (Exception e) {
            AppLog.e(TAG, "处理消息失败", e);
        }
    }

    /**
     * 解析指令文本
     * 移除 @ 机器人名称部分
     */
    private String parseCommand(String text) {
        if (text == null) {
            return "";
        }

        // 移除 @botname 部分
        String command = text.replaceAll("@\\S+", "").trim();
        return command;
    }

    /**
     * 解析录制时长（秒）
     * 支持格式：/record、/record 30、录制、录制30、录制 30
     */
    /** 解析指令尾部的序号（如「视频2」→2），不是纯数字或越界返回 0 */
    private static int parseTrailingNumber(String command, String prefix) {
        if (command == null || prefix == null) return 0;
        if (!command.toLowerCase(java.util.Locale.US)
                .startsWith(prefix.toLowerCase(java.util.Locale.US))) return 0;
        String rest = command.substring(prefix.length()).trim();
        if (rest.isEmpty()) return 0;
        for (char c : rest.toCharArray()) {
            if (c < '0' || c > '9') return 0;
        }
        try {
            int v = Integer.parseInt(rest);
            return (v >= 1 && v <= 20) ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int parseRecordDuration(String command) {
        if (command == null || command.isEmpty()) {
            return 60;
        }

        // 移除指令关键字，提取数字
        String durationStr = command
                .replaceAll("(?i)(/record|录制|record)", "")
                .trim();

        if (durationStr.isEmpty()) {
            return 60; // 默认 1 分钟
        }

        try {
            int duration = Integer.parseInt(durationStr);
            // 限制范围：最少 5 秒，最多 600 秒（10分钟）
            if (duration < 5) {
                return 5;
            } else if (duration > 600) {
                return 600;
            }
            return duration;
        } catch (NumberFormatException e) {
            AppLog.w(TAG, "无法解析录制时长: " + durationStr + "，使用默认值 60 秒");
            return 60;
        }
    }

    /**
     * 发送响应消息，并在发送完成后执行回调
     */
    private void sendResponseAndThen(long chatId, String message, Runnable callback) {
        new Thread(() -> {
            try {
                apiClient.sendMessage(chatId, message);
                AppLog.d(TAG, "响应消息已发送: " + message);

                if (callback != null) {
                    callback.run();
                }
            } catch (Exception e) {
                AppLog.e(TAG, "发送响应消息失败", e);
                // 即使发送失败，也执行回调
                if (callback != null) {
                    callback.run();
                }
            }
        }).start();
    }

    /**
     * 停止消息轮询
     */
    public void stop() {
        AppLog.d(TAG, "正在停止 Bot...");
        shouldStop = true;
        isRunning = false;

        if (pollingThread != null) {
            pollingThread.interrupt();
            
            // 等待轮询线程完全结束，最多等待 35 秒（比 POLL_TIMEOUT 稍长）
            // 这样可以避免重启时新旧连接冲突导致 409 错误
            try {
                pollingThread.join(35000);
                if (pollingThread.isAlive()) {
                    AppLog.w(TAG, "轮询线程未能在超时内结束");
                } else {
                    AppLog.d(TAG, "轮询线程已完全停止");
                }
            } catch (InterruptedException e) {
                AppLog.w(TAG, "等待轮询线程停止时被中断");
                Thread.currentThread().interrupt();
            }
            pollingThread = null;
        }

        AppLog.d(TAG, "Bot 已停止");
    }

    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return isRunning;
    }
}
