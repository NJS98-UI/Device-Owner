package com.ahui.clustercast

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.Collator
import java.util.Locale

/**
 * 选择投屏应用：一排 3 个，图标在上、名字在下，不显示包名。
 * 选定后写进 Cfg，作为「跟随前台」拿不到前台时的兜底目标。
 */
class AppPicker : Activity() {

    private lateinit var cfg: Cfg
    private var all: List<ResolveInfo> = emptyList()
    private lateinit var grid: GridView
    private lateinit var search: EditText
    private lateinit var adapter: Adapter

    override fun onCreate(b: Bundle?) {
        Ui.fit960(this)
        super.onCreate(b)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        cfg = Cfg(this)
        all = loadApps()

        grid = GridView(this)
        grid.numColumns = 3
        // 不拉伸：格子保持固定宽度靠左排，屏幕再宽也不会摊满整行。
        grid.stretchMode = GridView.NO_STRETCH
        grid.columnWidth = Ui.dp(this, 150)
        grid.horizontalSpacing = Ui.dp(this, 10)
        grid.verticalSpacing = Ui.dp(this, 10)
        grid.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4))
        adapter = Adapter(all)
        grid.adapter = adapter
        // 必须按当前显示列表（可能是搜索过滤后的）取值，用 all 会选错甚至越界
        grid.setOnItemClickListener { _, _, pos, _ -> choose(adapter.getItem(pos)) }

        search = EditText(this)
        search.hint = "搜索应用名"
        search.maxLines = 1
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
        search.setHintTextColor(Ui.INK_FAINT)
        search.setTextColor(Ui.INK)
        search.background = Ui.paint(this, Ui.R_FIELD, 10)
        search.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { filter(s) }
            override fun afterTextChanged(s: Editable?) { }
        })

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = Ui.text(this, 17, Ui.INK, Typeface.BOLD, 1)
        title.text = "选择投屏应用"
        head.addView(title, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val done = Ui.button(this, "完成", 13, false)
        Ui.click(done) { finish() }
        head.addView(done, LinearLayout.LayoutParams(
                Ui.dp(this, 64), Ui.dp(this, 32)))
        col.addView(head, Ui.lw())

        val sp = Ui.lw()
        sp.topMargin = Ui.dp(this, 8)
        sp.height = Ui.dp(this, 32)
        col.addView(search, sp)

        val gp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        gp.topMargin = Ui.dp(this, 8)
        col.addView(grid, gp)

        grid.background = Ui.paint(this, Ui.R_CARD, 12)
        col.background = Ui.wallpaper(this)
        setContentView(col)
    }

    private fun loadApps(): List<ResolveInfo> {
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val rs = packageManager.queryIntentActivities(probe, 0)
        val out = ArrayList<ResolveInfo>()
        for (r in rs) if (r.activityInfo.packageName != packageName) out.add(r)
        val coll = Collator.getInstance(Locale.CHINA)
        out.sortWith(Comparator { x, y ->
            coll.compare(x.loadLabel(packageManager).toString(),
                    y.loadLabel(packageManager).toString())
        })
        return out
    }

    private fun filter(s: CharSequence?) {
        val q = (s?.toString() ?: "").trim().lowercase(Locale.getDefault())
        adapter.reset(if (q.isEmpty()) all
        else all.filter {
            it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()).contains(q)
        })
    }

    private fun choose(r: ResolveInfo) {
        val ai = r.activityInfo.applicationInfo
        val name = packageManager.getApplicationLabel(ai).toString()
        cfg.setTarget(r.activityInfo.packageName, r.activityInfo.name, name)
        android.widget.Toast.makeText(this, "已选定：" + name,
                android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }

    /** 一格：图标在上、名字在下。convertView 可能为 null，必须先判空再复用。 */
    private inner class Adapter(var items: List<ResolveInfo>) : BaseAdapter() {

        fun reset(list: List<ResolveInfo>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(pos: Int): ResolveInfo = items[pos]
        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun getView(pos: Int, cv: View?, parent: ViewGroup?): View {
            val cell = (cv as? LinearLayout) ?: LinearLayout(this@AppPicker).let {
                it.orientation = LinearLayout.VERTICAL
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.setPadding(Ui.dp(this@AppPicker, 4), Ui.dp(this@AppPicker, 8),
                        Ui.dp(this@AppPicker, 4), Ui.dp(this@AppPicker, 8))
                it.background = Ui.paint(this@AppPicker, Ui.R_BTN, 10)
                it.addView(ImageView(this@AppPicker), LinearLayout.LayoutParams(
                        Ui.dp(this@AppPicker, 40), Ui.dp(this@AppPicker, 40)))
                it.addView(Ui.text(this@AppPicker, 11, Ui.INK, Typeface.NORMAL, 1).apply {
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@AppPicker, 6) })
                it
            }
            val r = items[pos]
            (cell.getChildAt(0) as ImageView).setImageDrawable(r.loadIcon(packageManager))
            (cell.getChildAt(1) as android.widget.TextView).text =
                    r.loadLabel(packageManager).toString()
            val size = Ui.dp(this@AppPicker, 120)
            cell.layoutParams = android.widget.AbsListView.LayoutParams(size, size)
            return cell
        }
    }
}
