package com.ahui.vehprobe;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 车控写验证包：把「原车应用这么写」升级成「我们自己写、MCU 认、而且真的执行了」。
 *
 * 每一项都是同一套流程：读原值 → 下发目标值 → 在 +0.3/1/2/3/5s 各回读一次 → 按回读值判 OK / NOCHANGE / ...
 * 判定**只认回读**，不认"反射没抛异常"（总线是 fire-and-forget，见 Bus.set 的注释）。
 *
 * 所有结论同时以 `RESULT|` 开头的单行日志落到 logcat，方便事后离线对时间线。
 */
public class MainActivity extends Activity {

    private static final String TAG = "VehProbe";
    private static final long[] MARKS = {300, 1000, 2000, 3000, 5000};

    // ---------- 协议项 ----------

    static final class Item {
        final String label;
        final int ev;
        final int cmd;
        final String kind;
        final String[] btnNames;
        final int[][] btnVals;
        volatile boolean busy;
        TextView value;

        Item(String label, int ev, int cmd, String kind, String[] btnNames, int[][] btnVals) {
            this.label = label;
            this.ev = ev;
            this.cmd = cmd;
            this.kind = kind;
            this.btnNames = btnNames;
            this.btnVals = btnVals;
        }
    }

    /**
     * 值域含义全部来自实机抓包 + 原车 APK 反编译，见 yibiao/协议说明-*.md。
     * ⚠ 开关类是**两套编码**：下发用 1=关/2=开，getOnce 回读是 0=关/1=开
     *   （实测：写 A/C=1 → 读回 0；写 AUTO=2 → 读回 1）。所以下面的表是「读值」侧的。
     */
    private static final String[] SW = {"0=关", "1=开"};
    private static final String[] WIN = {"0=空闲", "1=关", "2=开", "3=透气", "4=运行中"};
    private static final String[] GEAR = {"1=P", "2=R", "3=N", "4=D"};
    private static final String[] AIRMODE = {"0=吹面", "1=面脚", "2=脚", "3=脚霜", "4=霜", "5=面霜", "6=面脚霜"};
    private static final String[] SEAT = {"1=关", "2=加热3档", "6=加热2档", "10=加热1档",
            "17=通风3档", "21=通风2档", "25=通风1档"};

    /** 把原始 int[] 翻成人话，逐元素解码。 */
    static String decode(String kind, int[] v) {
        if (v == null || v.length == 0) return "-";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) sb.append(i > 0 ? "/" : "").append(one(kind, v[i]));
        return sb.toString();
    }

    /**
     * 下发值 → 期望回读值。开关类两套编码（写 1/2、读 0/1），其余原样。
     * 判定必须拿这个比，不能拿下发值比，否则正确的写入会被误判成 NOCHANGE。
     */
    static int[] expect(Item it, int[] target) {
        if (!"sw".equals(it.kind)) return target;
        int[] e = new int[target.length];
        for (int i = 0; i < target.length; i++) e[i] = target[i] - 1;
        return e;
    }

    static String one(String kind, int x) {
        switch (kind) {
            case "sw": return match(SW, x, "开关");
            case "win": return match(WIN, x, "车窗");
            case "gear": return match(GEAR, x, "挡");
            case "air": return match(AIRMODE, x, "模式");
            case "seat": return match(SEAT, x, "座椅");
            case "temp": return x == Integer.MIN_VALUE ? "-" : (x / 2) + "℃(" + x + ")";
            case "fan": return x == Integer.MIN_VALUE ? "-" : "档" + x + "(" + x + ")";
            default: return String.valueOf(x);
        }
    }

    private static String match(String[] table, int x, String pfx) {
        for (String s : table) {
            if (s.startsWith(x + "=")) return s.substring(s.indexOf('=') + 1) + "(" + x + ")";
        }
        return x == Integer.MIN_VALUE ? "-" : pfx + x;
    }

    private static Item[] buildItems() {
        int[][] swOffOn = {{1}, {2}};
        String[] offOn = {"写关1", "写开2"};
        return new Item[] {
            new Item("挡位(只读)", Bus.EV_VEHICLE_STATE, 26, "gear", new String[0], new int[0][]),

            // ===== 空调 327690 =====
            new Item("A/C", Bus.EV_HVAC, 1, "sw", offOn, swOffOn),
            new Item("AUTO", Bus.EV_HVAC, 4, "sw", offOn, swOffOn),
            new Item("SYNC", Bus.EV_HVAC, 91, "sw", offOn, swOffOn),
            new Item("内外循环", Bus.EV_HVAC, 8, "sw", new String[] {"写1", "写2"}, swOffOn),
            new Item("出风模式", Bus.EV_HVAC, 6, "air",
                    new String[] {"面0", "面脚1", "脚2", "霜3"}, new int[][] {{0}, {1}, {2}, {3}}),
            new Item("主驾温度", Bus.EV_HVAC, 10, "temp",
                    new String[] {"18℃", "22℃", "26℃"}, new int[][] {{36}, {44}, {52}}),
            new Item("副驾温度", Bus.EV_HVAC, 13, "temp",
                    new String[] {"18℃", "22℃", "26℃"}, new int[][] {{36}, {44}, {52}}),
            new Item("风量", Bus.EV_HVAC, 12, "fan",
                    new String[] {"1", "3", "5", "7"}, new int[][] {{1}, {3}, {5}, {7}}),
            new Item("MAX前除霜", Bus.EV_HVAC, 24, "sw", offOn, swOffOn),
            new Item("后排出风", Bus.EV_HVAC, 111, "sw", offOn, swOffOn),
            new Item("后排风量", Bus.EV_HVAC, 68, "fan",
                    new String[] {"1", "3", "5"}, new int[][] {{1}, {3}, {5}}),

            // ===== 座椅 / 方向盘 327681 =====
            new Item("主驾加热通风", Bus.EV_CAR_SETTING, 200, "seat",
                    new String[] {"关1", "热1=10", "热3=2", "风1=25", "风3=17"},
                    new int[][] {{1}, {10}, {2}, {25}, {17}}),
            new Item("副驾加热通风", Bus.EV_CAR_SETTING, 211, "seat",
                    new String[] {"关1", "热1=10", "热3=2", "风1=25", "风3=17"},
                    new int[][] {{1}, {10}, {2}, {25}, {17}}),
            new Item("方向盘加热", Bus.EV_CAR_SETTING, 160, "sw", offOn, swOffOn),

            // ===== 车窗 327681 =====
            win("主驾车窗", 162), win("副驾车窗", 163), win("左后车窗", 164), win("右后车窗", 165),
            new Item("一键四窗", Bus.EV_CAR_SETTING, 175, "win",
                    new String[] {"全开", "全关"},
                    new int[][] {{2, 2, 2, 2}, {1, 1, 1, 1}}),
        };
    }

    private static Item win(String label, int cmd) {
        return new Item(label, Bus.EV_CAR_SETTING, cmd, "win",
                new String[] {"关1", "开2", "透气3"}, new int[][] {{1}, {2}, {3}});
    }

    // ---------- UI ----------

    private final List<String> logLines = new ArrayList<>();
    private Bus bus;
    private Item[] items;
    private TextView header, logView;
    private HandlerThread ht;
    private Handler bg;
    private volatile boolean probing;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        items = buildItems();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(6), dp(8), dp(4));

        header = new TextView(this);
        header.setTextColor(Color.YELLOW);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setText("连接中…");
        root.addView(header);

        ScrollView sc = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (Item it : items) rows.addView(makeRow(it));
        sc.addView(rows);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(Color.LTGRAY);
        ScrollView lsc = new ScrollView(this);
        lsc.addView(logView);
        root.addView(lsc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

        setContentView(root);

        ht = new HandlerThread("bus");
        ht.start();
        bg = new Handler(ht.getLooper());
        bg.post(this::start);
    }

    private LinearLayout makeRow(final Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        name.setText(it.label + "\n" + Integer.toHexString(it.ev) + "/" + it.cmd);
        row.addView(name, new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));

        it.value = new TextView(this);
        it.value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        it.value.setTextColor(Color.CYAN);
        it.value.setText("…");
        row.addView(it.value, new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < it.btnNames.length; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(it.btnNames[i]);
            b.setAllCaps(false);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(6), 0, dp(6), 0);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            b.setOnClickListener(v -> probe(it, it.btnVals[idx], it.btnNames[idx]));
            row.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return row;
    }

    // ---------- 总线 ----------

    private void start() {
        bus = Bus.connect(this);
        runOnUiThread(() -> header.setText(bus.ok()
                ? "CAR_INFO 已绑定 · 只读轮询中；点按钮才是真下发，判定只看回读"
                : "绑定失败：" + bus.lastError()));
        bg.post(this::poll);
    }

    /** 1.5 秒一轮全量只读，用来观测瞬时态（如车窗 4=运行中）和挡位。 */
    private void poll() {
        if (bus == null) return;
        for (final Item it : items) {
            if (it.busy) continue;
            final Bus.R r = bus.get(it.ev, it.cmd);
            final String txt = r.good() ? decode(it.kind, r.value) : r.text();
            runOnUiThread(() -> it.value.setText(txt));
        }
        bg.postDelayed(this::poll, 1500);
    }

    private void probe(final Item it, final int[] target, final String btn) {
        Bus b = bus;
        if (b == null || !b.ok()) { note("总线还没绑上（或绑定失败），什么都不发"); return; }
        if (probing) { note("上一个探测还没跑完（约 5 秒）"); return; }
        probing = true;
        it.busy = true;
        note("下发 " + it.label + " " + btn + " target=" + Arrays.toString(target) + "，回读 5 秒");
        bg.post(() -> run(it, target, btn));
    }

    private void run(Item it, int[] target, String btn) {
        int[] want = expect(it, target);
        Bus.R before = bus.get(it.ev, it.cmd);
        boolean sent = bus.set(it.ev, it.cmd, target);

        StringBuilder samples = new StringBuilder();
        long prev = 0;
        Bus.R last = null;
        boolean endHit = false, anyHit = false;
        for (long mark : MARKS) {
            sleep(mark - prev);
            prev = mark;
            last = bus.get(it.ev, it.cmd);
            boolean m = Arrays.equals(last.value, want);
            samples.append('+').append(mark).append("ms=").append(last.text()).append(m ? "*" : " ").append(' ');
            if (m) anyHit = true;
            if (mark == MARKS[MARKS.length - 1]) endHit = m;
        }
        boolean moved = last != null && !Arrays.equals(last.value, before.value);

        String verdict;
        if (!sent) verdict = "SEND_FAIL";
        else if (endHit) verdict = "OK";
        else if (anyHit) verdict = "OK_TRANSIENT";
        else if (!last.good()) verdict = "NO_READBACK";
        else if (moved) verdict = "WRONGVAL";
        else if (Arrays.equals(before.value, want)) verdict = "ALREADY";
        else verdict = "NOCHANGE";

        final String line = "RESULT|" + it.label + "|ev=0x" + Integer.toHexString(it.ev) + "|cmd=" + it.cmd
                + "|btn=" + btn + "|target=" + Arrays.toString(target) + "|want=" + Arrays.toString(want)
                + "|before=" + before.text() + "|samples=" + samples.toString().trim()
                + "|verdict=" + verdict;
        final String v = verdict;
        final Bus.R fLast = last, fBefore = before;
        Log.i(TAG, line);
        runOnUiThread(() -> {
            it.value.setText(decode(it.kind, want) + " → " + v);
            it.value.setTextColor(v.startsWith("OK") ? Color.GREEN : "SEND_FAIL".equals(v) ? Color.RED : Color.WHITE);
            note(v + "  " + it.label + " 原=" + fBefore.text() + " 期望=" + Arrays.toString(want)
                    + " 末=" + fLast.text());
            probing = false;
            it.busy = false;
        });
    }

    private void note(String s) {
        Log.i(TAG, s);
        runOnUiThread(() -> {
            logLines.add(s);
            int from = Math.max(0, logLines.size() - 40);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < logLines.size(); i++) sb.append(logLines.get(i)).append('\n');
            logView.setText(sb);
        });
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ht != null) ht.quitSafely();
    }
}
