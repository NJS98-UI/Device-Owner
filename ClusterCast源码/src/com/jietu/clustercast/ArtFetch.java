package com.jietu.clustercast;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 联网取歌曲封面：酷狗车机版的 MediaSession 不带封面（实车 mediadump 实锤，
 * 元数据只有 8 个 key、无 ART/ALBUM_ART）。按 歌名+歌手 搜一张：
 * 先 NetEase 搜索接口，败了再试酷狗 songsearch；都失败记 FAILED 不再重试。
 * 断网/搜不到时调用方保持自己的占位图。
 */
final class ArtFetch {
    private static final String TAG = "ClusterCast";

    interface Done { void got(Bitmap b); }

    private static final ExecutorService EX = Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String, Bitmap> CACHE = new ConcurrentHashMap<String, Bitmap>();
    private static final ConcurrentHashMap<String, Boolean> PENDING = new ConcurrentHashMap<String, Boolean>();
    /** 失败记录带时间戳：60s 内不重试（防刷屏），之后允许再试——网络抖动不该永久哑掉 */
    private static final ConcurrentHashMap<String, Long> FAILED = new ConcurrentHashMap<String, Long>();
    private static final long RETRY_MS = 60000;

    private ArtFetch() {}

    static void fetch(Context ctx, String title, String artist, Done done) {
        final String key = title + "|" + artist;
        Bitmap hit = CACHE.get(key);
        if (hit != null && !hit.isRecycled()) { done.got(hit); return; }
        Long fail = FAILED.get(key);
        if (PENDING.containsKey(key) || (fail != null && System.currentTimeMillis() - fail < RETRY_MS)) return;
        FAILED.remove(key);
        PENDING.put(key, Boolean.TRUE);
        final String kw = (title + " " + (artist == null ? "" : artist)).trim();
        EX.execute(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                try { b = fromNetEase(kw); } catch (Throwable t) {
                    Log.w(TAG, "ArtFetch netease: " + t);
                }
                if (b == null) {
                    try { b = fromKugou(kw); } catch (Throwable t) {
                        Log.w(TAG, "ArtFetch kugou: " + t);
                    }
                }
                PENDING.remove(key);
                if (b != null) {
                    CACHE.put(key, b);
                    done.got(b);
                    Log.i(TAG, "ArtFetch 已取到封面 " + kw + " " + b.getWidth() + "x" + b.getHeight());
                } else {
                    FAILED.put(key, System.currentTimeMillis());
                    Log.i(TAG, "ArtFetch 没搜到封面 " + kw);
                }
            }
        });
    }

    /** NetEase 搜索：song.picId → 加密出封面 URL（接口已不下发 picUrl，实锤 2026-10-01） */
    private static Bitmap fromNetEase(String kw) throws Exception {
        String u = "http://music.163.com/api/search/get/web?s="
                + URLEncoder.encode(kw, "UTF-8") + "&type=1&limit=1";
        String body = httpGet(u);
        if (body == null) return null;
        org.json.JSONObject root = new org.json.JSONObject(body);
        org.json.JSONObject song = root.getJSONObject("result").getJSONArray("songs").getJSONObject(0);
        String pic = song.getJSONObject("album").optString("picUrl", null);
        if (pic == null || pic.length() == 0) {
            String pid = song.optString("picId", null);
            if (pid == null || pid.length() == 0 || "0".equals(pid))
                pid = song.getJSONObject("album").optString("picId", null);
            if (pid != null && pid.length() > 0 && !"0".equals(pid)) pic = neteasePicUrl(pid);
        }
        return pic != null ? download(pic) : null;
    }

    /** picId→URL：XOR magic 循环 → MD5 → Base64(URL-safe)，PC 实测能取回封面图 */
    private static String neteasePicUrl(String picId) throws Exception {
        byte[] magic = "3go8&$8*3*3h0k(2)2".getBytes("UTF-8");
        byte[] b = picId.getBytes("UTF-8");
        byte[] x = new byte[b.length];
        for (int i = 0; i < b.length; i++) x[i] = (byte) (b[i] ^ magic[i % magic.length]);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        String enc = android.util.Base64.encodeToString(md.digest(x), android.util.Base64.NO_WRAP);
        enc = enc.replace("/", "_").replace("+", "-").trim();
        return "http://p3.music.126.net/" + enc + "/" + picId + ".jpg";
    }

    /** 酷狗搜索：data.lists[0].imgurl（带 {size} 占位） */
    private static Bitmap fromKugou(String kw) throws Exception {
        String u = "https://songsearch.kugou.com/song_search_v2?keyword="
                + URLEncoder.encode(kw, "UTF-8") + "&page=1";
        String body = httpGet(u);
        if (body == null) return null;
        org.json.JSONObject root = new org.json.JSONObject(body);
        org.json.JSONArray lists = root.getJSONObject("data").getJSONArray("lists");
        String img = lists.getJSONObject(0).getString("imgurl");
        if (img == null) return null;
        return download(img.replace("{size}", "480"));
    }

    // ---------- 歌词（NetEase：搜索拿歌曲 id → lyric 接口拿 LRC） ----------

    interface LyricDone { void got(String lrc); }

    private static final ConcurrentHashMap<String, String> LCACHE = new ConcurrentHashMap<String, String>();
    private static final ConcurrentHashMap<String, Boolean> LPENDING = new ConcurrentHashMap<String, Boolean>();
    private static final ConcurrentHashMap<String, Long> LFAILED = new ConcurrentHashMap<String, Long>();

    /** 按 歌名+歌手 拉 LRC 原文（带时间轴）。失败 60s 后允许重试，回调可能在子线程。 */
    static void fetchLyric(String title, String artist, final LyricDone done) {
        final String key = title + "|" + artist;
        String hit = LCACHE.get(key);
        if (hit != null) { done.got(hit); return; }
        Long fail = LFAILED.get(key);
        if (LPENDING.containsKey(key) || (fail != null && System.currentTimeMillis() - fail < RETRY_MS)) return;
        LFAILED.remove(key);
        LPENDING.put(key, Boolean.TRUE);
        final String kw = (title + " " + (artist == null ? "" : artist)).trim();
        EX.execute(new Runnable() {
            @Override public void run() {
                String lrc = null;
                try {
                    String u = "http://music.163.com/api/search/get/web?s="
                            + URLEncoder.encode(kw, "UTF-8") + "&type=1&limit=1";
                    String body = httpGet(u);
                    if (body != null) {
                        org.json.JSONObject root = new org.json.JSONObject(body);
                        long id = root.getJSONObject("result").getJSONArray("songs")
                                .getJSONObject(0).getLong("id");
                        String lu = "http://music.163.com/api/song/lyric?id=" + id
                                + "&lv=1&kv=1&tv=-1";
                        String lb = httpGet(lu);
                        if (lb != null) {
                            org.json.JSONObject lr = new org.json.JSONObject(lb);
                            if (lr.has("lrc")) lrc = lr.getJSONObject("lrc").optString("lyric");
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "ArtFetch lyric: " + t);
                }
                LPENDING.remove(key);
                if (lrc != null && lrc.trim().length() > 0) {
                    LCACHE.put(key, lrc);
                    done.got(lrc);
                    Log.i(TAG, "ArtFetch 已取到歌词 " + kw);
                } else {
                    LFAILED.put(key, System.currentTimeMillis());
                    Log.i(TAG, "ArtFetch 没搜到歌词 " + kw);
                }
            }
        });
    }

    private static String httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = c.getResponseCode();
            if (code != 200) return null;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.io.InputStream is = c.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static Bitmap download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            if (c.getResponseCode() != 200) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            return BitmapFactory.decodeStream(c.getInputStream(), null, o);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }
}
