package com.kooo.evcam.dingtalk;

import android.content.Context;
import android.content.SharedPreferences;

import com.kooo.evcam.RemoteConfigMirror;

import java.util.HashMap;
import java.util.Map;

/**
 * 钉钉配置存储工具类。
 * 保存走同步 commit + 回读结果，并镜像到外部存储；prefs 丢失时由
 * restoreIfEmpty() 从镜像恢复（车机 /data 可能写不进或被清）。
 */
public class DingTalkConfig {
    private static final String PREF_NAME = "dingtalk_config";
    private static final String KEY_CLIENT_ID = "client_id";
    private static final String KEY_CLIENT_SECRET = "client_secret";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_TOKEN_EXPIRE_TIME = "token_expire_time";
    private static final String KEY_WEBHOOK_URL = "webhook_url";
    private static final String KEY_AUTO_START = "auto_start";

    private final Context ctx;
    private final SharedPreferences prefs;

    public DingTalkConfig(Context context) {
        ctx = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean saveConfig(String clientId, String clientSecret) {
        boolean ok = prefs.edit()
                .putString(KEY_CLIENT_ID, clientId)
                .putString(KEY_CLIENT_SECRET, clientSecret)
                .commit();
        if (ok) {
            Map<String, String> kv = new HashMap<>();
            kv.put(KEY_CLIENT_ID, clientId);
            kv.put(KEY_CLIENT_SECRET, clientSecret);
            RemoteConfigMirror.mirror(ctx, PREF_NAME, kv);
        }
        return ok;
    }

    /** prefs 里配置丢了就从外部镜像恢复；返回是否恢复了。 */
    public boolean restoreIfEmpty() {
        if (isConfigured()) return false;
        java.util.Properties p = RemoteConfigMirror.read(ctx, PREF_NAME);
        if (p == null) return false;
        String id = p.getProperty(KEY_CLIENT_ID, "");
        String secret = p.getProperty(KEY_CLIENT_SECRET, "");
        if (id.isEmpty() || secret.isEmpty()) return false;
        prefs.edit()
                .putString(KEY_CLIENT_ID, id)
                .putString(KEY_CLIENT_SECRET, secret)
                .commit();
        return true;
    }

    public String getClientId() {
        return prefs.getString(KEY_CLIENT_ID, "");
    }

    public String getClientSecret() {
        return prefs.getString(KEY_CLIENT_SECRET, "");
    }

    public boolean isConfigured() {
        return !getClientId().isEmpty() && !getClientSecret().isEmpty();
    }

    public void saveAccessToken(String token, long expireTime) {
        prefs.edit()
                .putString(KEY_ACCESS_TOKEN, token)
                .putLong(KEY_TOKEN_EXPIRE_TIME, expireTime)
                .apply();
    }

    public String getAccessToken() {
        return prefs.getString(KEY_ACCESS_TOKEN, "");
    }

    public boolean isTokenValid() {
        long expireTime = prefs.getLong(KEY_TOKEN_EXPIRE_TIME, 0);
        return System.currentTimeMillis() < expireTime;
    }

    /**
     * 清除缓存的 AccessToken（用于测试连接时强制重新获取）
     */
    public void clearAccessToken() {
        prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_TOKEN_EXPIRE_TIME)
                .apply();
    }

    public void clearConfig() {
        prefs.edit().clear().apply();
    }

    public void saveWebhookUrl(String webhookUrl) {
        prefs.edit()
                .putString(KEY_WEBHOOK_URL, webhookUrl)
                .apply();
    }

    public String getWebhookUrl() {
        return prefs.getString(KEY_WEBHOOK_URL, "");
    }

    public void setAutoStart(boolean autoStart) {
        prefs.edit()
                .putBoolean(KEY_AUTO_START, autoStart)
                .commit();
    }

    public boolean isAutoStart() {
        // 配置过钉钉就默认跟随应用自启连接（未配置时 RemoteServiceManager 有 isConfigured 闸门）
        return prefs.getBoolean(KEY_AUTO_START, true);
    }
}
