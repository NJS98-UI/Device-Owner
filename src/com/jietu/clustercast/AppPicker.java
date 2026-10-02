package com.jietu.clustercast;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 选择投屏应用：一排 3 个，图标在上、名字在下，不显示包名。
 * 选定后写进 Cfg，作为「跟随前台」拿不到前台时的兜底目标。
 * 由 v5.1.0 的 AppPicker.java 原样移植（仅改包名）。
 */
public class AppPicker extends Activity {

    private Cfg cfg;
    private List<ResolveInfo> all = new ArrayList<>();
    private GridView grid;
    private EditText search;
    private Adapter adapter;

    @Override protected void onCreate(Bundle b) {
        Ui.fit1050(this);
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        cfg = new Cfg(this);
        all = loadApps();

        grid = new GridView(this);
        grid.setNumColumns(3);
        // 不拉伸：格子保持固定宽度靠左排，屏幕再宽也不会摊满整行。
        grid.setStretchMode(GridView.NO_STRETCH);
        grid.setColumnWidth(Ui.dp(this, 150));
        grid.setHorizontalSpacing(Ui.dp(this, 10));
        grid.setVerticalSpacing(Ui.dp(this, 10));
        grid.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        adapter = new Adapter(all);
        grid.setAdapter(adapter);
        // 必须按当前显示列表（可能是搜索过滤后的）取值，用 all 会选错甚至越界
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v,
                    int pos, long id) {
                choose(adapter.getItem(pos));
            }
        });

        search = new EditText(this);
        search.setHint("搜索应用名");
        search.setMaxLines(1);
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
        search.setHintTextColor(Ui.INK_FAINT);
        search.setTextColor(Ui.INK);
        search.setBackground(Ui.paint(this, Ui.R_FIELD, 10));
        search.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { filter(s); }
            @Override public void afterTextChanged(Editable s) { }
        });

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, 17, Ui.INK, Typeface.BOLD, 1);
        title.setText("选择投屏应用");
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = Ui.button(this, "完成", 13, false);
        Ui.click(done, new Runnable() {
            @Override public void run() { finish(); }
        });
        head.addView(done, new LinearLayout.LayoutParams(
                Ui.dp(this, 64), Ui.dp(this, 32)));
        col.addView(head, Ui.lw());

        LinearLayout.LayoutParams sp = Ui.lw();
        sp.topMargin = Ui.dp(this, 8);
        sp.height = Ui.dp(this, 32);
        col.addView(search, sp);

        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        gp.topMargin = Ui.dp(this, 8);
        col.addView(grid, gp);

        grid.setBackground(Ui.paint(this, Ui.R_CARD, 12));
        col.setBackground(Ui.wallpaper(this));
        setContentView(col);
    }

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

    private void filter(CharSequence s) {
        String s0 = s == null ? "" : s.toString();
        final String q = s0.trim().toLowerCase(Locale.getDefault());
        if (q.isEmpty()) { adapter.reset(all); return; }
        ArrayList<ResolveInfo> keep = new ArrayList<>();
        for (ResolveInfo r : all)
            if (r.loadLabel(getPackageManager()).toString()
                    .toLowerCase(Locale.getDefault()).contains(q)) keep.add(r);
        adapter.reset(keep);
    }

    private void choose(ResolveInfo r) {
        android.content.pm.ApplicationInfo ai = r.activityInfo.applicationInfo;
        String name = getPackageManager().getApplicationLabel(ai).toString();
        cfg.setTarget(r.activityInfo.packageName, r.activityInfo.name, name);
        // 跟随前台开着时，三指左滑投的是「当前前台应用」，选定目标永远轮不上。
        // 用户专门进来挑了应用就是明确意图：自动关掉跟随前台，让选择生效（v13）。
        if (cfg.followTop()) {
            cfg.setFollowTop(false);
            Toast.makeText(this, "已选定：" + name + "（已自动关闭跟随前台）", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "已选定：" + name, Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    /** 一格：图标在上、名字在下。convertView 可能为 null，必须先判空再复用。 */
    private class Adapter extends BaseAdapter {
        List<ResolveInfo> items;
        Adapter(List<ResolveInfo> l) { items = l; }
        void reset(List<ResolveInfo> list) { items = list; notifyDataSetChanged(); }
        @Override public int getCount() { return items.size(); }
        @Override public ResolveInfo getItem(int pos) { return items.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout cell;
            if (cv instanceof LinearLayout) cell = (LinearLayout) cv;
            else {
                cell = new LinearLayout(AppPicker.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(Ui.dp(AppPicker.this, 4), Ui.dp(AppPicker.this, 8),
                        Ui.dp(AppPicker.this, 4), Ui.dp(AppPicker.this, 8));
                cell.setBackground(Ui.paint(AppPicker.this, Ui.R_BTN, 10));
                cell.addView(new ImageView(AppPicker.this), new LinearLayout.LayoutParams(
                        Ui.dp(AppPicker.this, 40), Ui.dp(AppPicker.this, 40)));
                TextView t = Ui.text(AppPicker.this, 11, Ui.INK, Typeface.NORMAL, 1);
                t.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.topMargin = Ui.dp(AppPicker.this, 6);
                cell.addView(t, tlp);
            }
            ResolveInfo r = items.get(pos);
            ((ImageView) cell.getChildAt(0)).setImageDrawable(r.loadIcon(getPackageManager()));
            ((TextView) cell.getChildAt(1)).setText(r.loadLabel(getPackageManager()).toString());
            int size = Ui.dp(AppPicker.this, 120);
            cell.setLayoutParams(new AbsListView.LayoutParams(size, size));
            return cell;
        }
    }
}
