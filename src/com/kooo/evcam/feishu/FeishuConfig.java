package com.kooo.evcam.feishu;

import android.content.Context;
import android.content.SharedPreferences;

import com.kooo.evcam.RemoteConfigMirror;

import java.util.HashMap;
import java.util.Map;

/**
 * 飞书配置存储工具类。
 * 保存走同步 commit，并镜像到外部存储；prefs 丢失时由
 * restoreIfEmpty() 从镜像恢复（车机 /data 可能写不进或被清）。
 */
public class FeishuConfig {
    private static final String PREF_NAME = "feishu_config";
    private static final String KEY_APP_ID = "app_id";
    private static final String KEY_APP_SECRET = "app_secret";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_TOKEN_EXPIRE_TIME = "token_expire_time";
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_ALLOWED_USER_IDS = "allowed_user_ids";

    private final Context ctx;
    private final SharedPreferences prefs;

    public FeishuConfig(Context context) {
        ctx = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 保存配置
     * @param appId 应用 App ID
     * @param appSecret 应用 App Secret
     */
    public boolean saveConfig(String appId, String appSecret) {
        return saveConfig(appId, appSecret, getAllowedUserIds());
    }

    /**
     * 保存配置（包含允许的用户ID）
     */
    public boolean saveConfig(String appId, String appSecret, String allowedUserIds) {
        boolean ok = prefs.edit()
                .putString(KEY_APP_ID, appId)
                .putString(KEY_APP_SECRET, appSecret)
                .putString(KEY_ALLOWED_USER_IDS, allowedUserIds)
                .commit();
        if (ok) {
            Map<String, String> kv = new HashMap<>();
            kv.put(KEY_APP_ID, appId);
            kv.put(KEY_APP_SECRET, appSecret);
            kv.put(KEY_ALLOWED_USER_IDS, allowedUserIds == null ? "" : allowedUserIds);
            kv.put(KEY_AUTO_START, String.valueOf(isAutoStart()));
            RemoteConfigMirror.mirror(ctx, PREF_NAME, kv);
        }
        return ok;
    }

    /** prefs 里配置丢了就从外部镜像恢复；返回是否恢复了。 */
    public boolean restoreIfEmpty() {
        if (isConfigured()) return false;
        java.util.Properties p = RemoteConfigMirror.read(ctx, PREF_NAME);
        if (p == null) return false;
        String id = p.getProperty(KEY_APP_ID, "");
        String secret = p.getProperty(KEY_APP_SECRET, "");
        if (id.isEmpty() || secret.isEmpty()) return false;
        SharedPreferences.Editor e = prefs.edit()
                .putString(KEY_APP_ID, id)
                .putString(KEY_APP_SECRET, secret)
                .putString(KEY_ALLOWED_USER_IDS, p.getProperty(KEY_ALLOWED_USER_IDS, ""));
        String auto = p.getProperty(KEY_AUTO_START, "");
        if ("true".equals(auto) || "false".equals(auto)) e.putBoolean(KEY_AUTO_START, Boolean.parseBoolean(auto));
        e.commit();
        return true;
    }

    public String getAppId() {
        return prefs.getString(KEY_APP_ID, "");
    }

    public String getAppSecret() {
        return prefs.getString(KEY_APP_SECRET, "");
    }

    /**
     * 获取允许的用户ID列表
     * @return 逗号分隔的用户ID字符串
     */
    public String getAllowedUserIds() {
        return prefs.getString(KEY_ALLOWED_USER_IDS, "");
    }

    /**
     * 检查用户ID是否被允许
     * 如果未配置任何用户ID，则允许所有
     */
    public boolean isUserIdAllowed(String userId) {
        String allowedIds = getAllowedUserIds();
        if (allowedIds.isEmpty()) {
            return true; // 未配置时允许所有
        }

        String[] ids = allowedIds.split(",");
        for (String id : ids) {
            if (id.trim().equals(userId)) {
                return true;
            }
        }
        return false;
    }

    public boolean isConfigured() {
        return !getAppId().isEmpty() && !getAppSecret().isEmpty();
    }

    /**
     * 保存 Access Token
     */
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
     * 清除缓存的 AccessToken
     */
    public void clearAccessToken() {
        prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_TOKEN_EXPIRE_TIME)
                .apply();
    }

    public void setAutoStart(boolean autoStart) {
        prefs.edit()
                .putBoolean(KEY_AUTO_START, autoStart)
                .commit();
    }

    public boolean isAutoStart() {
        // 配置过飞书就默认跟随应用自启连接（未配置时 RemoteServiceManager 有 isConfigured 闸门）
        return prefs.getBoolean(KEY_AUTO_START, true);
    }

    public void clearConfig() {
        prefs.edit().clear().apply();
    }
}
