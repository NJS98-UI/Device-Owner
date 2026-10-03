package com.kooo.evcam.license;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;

import com.kooo.evcam.AppLog;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 一机一码激活/试用管理（车机记录仪）。
 *
 * 对接 http://103.149.200.173/update/ux.json：该文件顶层是其他软件的加密
 * 激活区块，本软件数据放顶层并行键 "jietu"（第二排），自有 AES-256-GCM
 * 密钥加密传输与存储。读写一律"读整个文件 → 只改自己的键 → 原样写回"，
 * 绝不动其他键（不删除/不修改旧数据）。
 *
 * 解密后 payload 结构：
 * {
 *   "pool":    {"codes": ["123456", ...]},              激活码池（管理工具写入）
 *   "records": {                                        机器码 → 授权记录
 *     "MC1234AB": {"s": "trial|act", "t": 1698..., "c": "123456"}
 *   },
 *   "update":  {"v": "1.1", "u": "http://.../xxx.apk"}  自更新版本与下载地址
 * }
 *
 * 规则：
 * - 机器码 = SHA256(签名SHA256 + ANDROID_ID) 前 8 位大写（签名固定，卸载
 *   重装 ANDROID_ID 不变 → 机器码不变 → 云端有记录即不能再试用）
 * - 一个机器码只能试用一次（48 小时，云端记录首次试用时间戳为准，本地
 *   改时钟无效——轮询用云端 ts 判定过期）
 * - 一个激活码绑一个机器码（已绑定的码不能再绑）
 * - 每 3 秒轮询云端；连续 2 次失败（含断网）→ 停用（BLOCKED）
 * - 试用/激活请求体与存储均为密文
 */
public final class LicenseManager {

    private static final String TAG = "LicenseManager";
    /** 服务器 ux.json（第二排 jietu 键）。 */
    private static final String LICENSE_URL = "http://103.149.200.173/update/ux.json";
    private static final String OUR_KEY = "jietu";
    /** AES-256 密钥（传输+存储共用）。 */
    private static final byte[] AES_KEY = hexBytes(
            "9b29563aa59172c73acbf5c61a90715bfe47d5c06283a46a263c0421182c7a92");
    private static final long TRIAL_MS = 48L * 3600_000L;
    private static final long POLL_MS = 3_000L;
    /** 连续失败 N 次判定断网/失联 → 停用（6 秒，容忍车机网络抖动）。 */
    private static final int FAIL_LIMIT = 2;

    public enum State { PENDING, NOT_ACTIVATED, TRIAL, ACTIVATED, BLOCKED }

    /** 状态变化回调（主线程）。 */
    public interface Listener {
        void onLicenseChanged(State state, long trialRemainingMs, String message);
    }

    private static final LicenseManager INST = new LicenseManager();
    public static LicenseManager get() { return INST; }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Context app;
    private Listener listener;
    private State state = State.PENDING;
    private long trialRemainingMs = 0;
    private String lastMessage = "";
    private int failStreak = 0;
    private boolean polling;
    /** 轮询开关（trial/activate 请求完成后立即触发一次额外轮询）。 */
    private boolean running;

    private LicenseManager() { }

    public synchronized void start(Context ctx, Listener l) {
        app = ctx.getApplicationContext();
        // 服务侧兜底启动传 null：不覆盖界面已注册的 listener
        if (l != null || listener == null) listener = l;
        if (running) return;
        running = true;
        io.execute(this::poll);
    }

    public State getState() { return state; }
    public long getTrialRemainingMs() { return trialRemainingMs; }
    public String getLastMessage() { return lastMessage; }
    /** 允许使用（录像等核心功能放行条件）。 */
    public boolean isAllowed() { return state == State.TRIAL || state == State.ACTIVATED; }

    /** 机器码：签名 SHA256 + ANDROID_ID → SHA256 → 前 8 位大写。 */
    public static String machineCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                    ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            String sig = sha256Hex(pi.signatures[0].toByteArray());
            String aid = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            return sha256Hex(sig + "|" + aid + "|jietu").substring(0, 8).toUpperCase();
        } catch (Throwable t) {
            AppLog.w(TAG, "机器码生成失败: " + t);
            return "UNKNOWN00";
        }
    }

    /** 申请 48 小时试用：云端无本机记录才写入（一机一试用）。回调主线程。 */
    public void requestTrial(TrialCallback cb) {
        final String mc = machineCode(app);
        io.execute(() -> {
            try {
                JSONObject root = fetchRoot();
                JSONObject payload = readPayload(root);
                JSONObject records = payload.optJSONObject("records");
                if (records == null) {
                    records = new JSONObject();
                    payload.put("records", records);
                }
                if (records.has(mc)) {
                    String s = records.getJSONObject(mc).optString("s", "");
                    postMain(() -> cb.onResult(false,
                            "act".equals(s) ? "本机已激活，无需试用"
                                            : "本机已试用过，无法再次试用（重装也一样）"));
                    return;
                }
                JSONObject rec = new JSONObject();
                rec.put("s", "trial");
                rec.put("t", serverNow());
                rec.put("n", 1);
                records.put(mc, rec);
                upload(writePayload(root, payload));
                AppLog.d(TAG, "试用已上传 " + mc);
                io.execute(this::poll);
                postMain(() -> cb.onResult(true, "试用已开启（48 小时）"));
            } catch (Throwable t) {
                AppLog.w(TAG, "试用申请失败: " + t);
                postMain(() -> cb.onResult(false, "网络异常，试用申请失败"));
            }
        });
    }

    /** 激活：码须在池中且未绑定其他机器码，绑定后写回。回调主线程。 */
    public void activate(String code, TrialCallback cb) {
        final String c = code == null ? "" : code.trim();
        if (!c.matches("\\d{6}")) {
            postMain(() -> cb.onResult(false, "激活码为 6 位数字"));
            return;
        }
        final String mc = machineCode(app);
        io.execute(() -> {
            try {
                JSONObject root = fetchRoot();
                JSONObject payload = readPayload(root);
                JSONObject pool = payload.optJSONObject("pool");
                boolean inPool = false;
                if (pool != null) {
                    org.json.JSONArray codes = pool.optJSONArray("codes");
                    if (codes != null) {
                        for (int i = 0; i < codes.length(); i++) {
                            if (c.equals(codes.optString(i))) { inPool = true; break; }
                        }
                    }
                }
                if (!inPool) {
                    postMain(() -> cb.onResult(false, "激活码不存在，请核对后重试"));
                    return;
                }
                JSONObject records = payload.optJSONObject("records");
                if (records == null) {
                    records = new JSONObject();
                    payload.put("records", records);
                }
                // 一码一机：已被其他机器码绑定的激活码拒绝
                java.util.Iterator<String> it = records.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject r = records.optJSONObject(k);
                    if (r != null && c.equals(r.optString("c", "")) && !mc.equals(k)) {
                        postMain(() -> cb.onResult(false, "该激活码已被其他设备使用"));
                        return;
                    }
                }
                JSONObject rec = records.has(mc) ? records.getJSONObject(mc) : new JSONObject();
                rec.put("s", "act");
                rec.put("c", c);
                if (!rec.has("t")) rec.put("t", serverNow());
                records.put(mc, rec);
                upload(writePayload(root, payload));
                AppLog.d(TAG, "激活成功 " + mc + " ← " + c);
                io.execute(this::poll);
                postMain(() -> cb.onResult(true, "激活成功，感谢支持"));
            } catch (Throwable t) {
                AppLog.w(TAG, "激活失败: " + t);
                postMain(() -> cb.onResult(false, "网络异常，激活失败"));
            }
        });
    }

    public interface TrialCallback { void onResult(boolean ok, String message); }

    // ---------- 轮询与判定 ----------

    private void poll() {
        if (!running) return;
        boolean stopLoop = false;
        try {
            JSONObject root = fetchRoot();
            JSONObject payload = readPayload(root);
            String mc = machineCode(app);
            JSONObject rec = payload.optJSONObject("records") == null
                    ? null : payload.getJSONObject("records").optJSONObject(mc);
            JSONObject upd = payload.optJSONObject("update");
            State ns;
            long remain = 0;
            String msg = "";
            if (rec != null && "act".equals(rec.optString("s"))) {
                ns = State.ACTIVATED;
            } else if (rec != null && "trial".equals(rec.optString("s"))) {
                long elapsed = serverNow() - rec.optLong("t", 0);
                if (elapsed < 0) elapsed = 0;
                if (elapsed < TRIAL_MS) {
                    ns = State.TRIAL;
                    remain = TRIAL_MS - elapsed;
                    msg = "试用剩余 " + formatRemain(remain);
                } else {
                    ns = State.BLOCKED;
                    msg = "试用已到期，请激活";
                }
            } else {
                ns = State.NOT_ACTIVATED;
                msg = "未激活，请试用或输入激活码";
                // 未激活：云端检测无意义，停轮询等用户点"试用/激活"后再开始
                stopLoop = true;
            }
            failStreak = 0;
            applyState(ns, remain, msg, upd);
        } catch (Throwable t) {
            failStreak++;
            AppLog.w(TAG, "轮询失败 " + failStreak + ": " + t);
            if (failStreak >= FAIL_LIMIT) {
                applyState(State.BLOCKED, 0, "无法连接激活服务器，已停止使用", null);
            }
        }
        // 未激活停轮询；其余（试用/已激活/断网停用）保持 3 秒检测——
        // 断网恢复后可自动回到试用/已激活状态
        if (running && !stopLoop) {
            main.postDelayed(() -> io.execute(this::poll), POLL_MS);
        }
    }

    private void applyState(State ns, long remain, String msg, JSONObject upd) {
        LicenseListenerCache.update = upd;
        boolean changed = ns != state || remain != trialRemainingMs || !msg.equals(lastMessage);
        State old = state;
        state = ns;
        trialRemainingMs = remain;
        lastMessage = msg;
        // 无界面兜底：allowed→denied 边沿直接停服务侧录像（试用到期/断网即时生效），
        // 有界面时 MainActivity 会再走一遍（stop 幂等）
        boolean denied = ns == State.NOT_ACTIVATED || ns == State.BLOCKED;
        boolean wasAllowed = old == State.TRIAL || old == State.ACTIVATED
                || old == State.PENDING;
        if (denied && wasAllowed && changed) {
            try {
                com.jietu.clustercast.QuadAutoRecord.stop(app);
            } catch (Throwable t) {
                AppLog.w(TAG, "授权失效停录失败: " + t);
            }
        }
        if (changed && listener != null) {
            postMain(() -> listener.onLicenseChanged(state, trialRemainingMs, lastMessage));
        }
    }

    /** 供 UpdateChecker 读取的最新自更新信息（v/u），非主线程勿用 JSONObject 后再解。 */
    public static JSONObject lastUpdateInfo() { return LicenseListenerCache.update; }

    private static final class LicenseListenerCache {
        static volatile JSONObject update;
    }

    private static String formatRemain(long ms) {
        long h = ms / 3600_000L;
        long m = (ms % 3600_000L) / 60_000L;
        return String.format("%02d:%02d", h, m);
    }

    // ---------- ux.json 读写（只动自己的键） ----------

    /** GET 整个 ux.json；顺带记录服务器时间（HTTP Date 头，防本地改时钟）。 */
    private static JSONObject fetchRoot() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(LICENSE_URL).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            String date = c.getHeaderField("Date");
            if (date != null) {
                try {
                    java.util.Date d = new java.text.SimpleDateFormat(
                            "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                            .parse(date);
                    if (d != null) serverTimeMs = d.getTime();
                } catch (Throwable ignore) { }
            }
            return new JSONObject(readAll(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    /** 最近一次成功 GET 的服务器时间；取不到回退本地时钟。 */
    private static volatile long serverTimeMs = 0;

    private static long serverNow() {
        return serverTimeMs > 0 ? serverTimeMs : System.currentTimeMillis();
    }

    /** PUT 整个 ux.json（root 内其他键原样保留，只含本次对 jietu 键的修改）。 */
    private static void upload(JSONObject root) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(LICENSE_URL).openConnection();
        c.setRequestMethod("PUT");
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try {
            byte[] body = root.toString().getBytes(StandardCharsets.UTF_8);
            c.getOutputStream().write(body);
            c.getOutputStream().flush();
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
        } finally {
            c.disconnect();
        }
    }

    /** 读出 jietu 区块并解密；不存在则初始化空结构（写时才会落盘）。 */
    private static JSONObject readPayload(JSONObject root) throws Exception {
        JSONObject box = root.optJSONObject(OUR_KEY);
        if (box == null) return new JSONObject();
        String data = box.optString("data", "");
        if (data.length() == 0) return new JSONObject();
        return new JSONObject(decrypt(data));
    }

    /** 加密 payload 塞回 root 的 jietu 键（其他键不动），随后 upload(root) 上云。 */
    private static JSONObject writePayload(JSONObject root, JSONObject payload) throws Exception {
        root.put(OUR_KEY, new JSONObject()
                .put("schema", "jietu_v1")
                .put("alg", "AES-256-GCM")
                .put("data", encrypt(payload.toString())));
        return root;
    }

    // ---------- AES-256-GCM ----------

    private static String encrypt(String plain) throws Exception {
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"),
                new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static String decrypt(String data) throws Exception {
        byte[] all = Base64.decode(data, Base64.NO_WRAP);
        byte[] iv = new byte[12];
        System.arraycopy(all, 0, iv, 0, 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"),
                new GCMParameterSpec(128, iv));
        byte[] pt = cipher.doFinal(all, 12, all.length - 12);
        return new String(pt, StandardCharsets.UTF_8);
    }

    // ---------- 工具 ----------

    private static String sha256Hex(String s) throws Exception {
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] hexBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private void postMain(Runnable r) { main.post(r); }
}
