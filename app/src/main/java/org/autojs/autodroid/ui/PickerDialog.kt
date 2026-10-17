package org.autojs.autodroid.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.autojs.autojs6.R

/**
 * 通用「圆角卡片 + 搜索」选择弹窗，供定时任务的目标选择与用例列表的目录选择共用，
 * 保证两处交互与视觉一致（高度随结果条数自适应、空态提示、选中态高亮）。
 */
object PickerDialog {

    /**
     * 一行选项：[key] 为业务主键（目录可为空表示"全部"），[badge] 为右侧胶囊文案，
     * [highlight] 标记当前选中项（名称与胶囊用主色），[searchKey] 供搜索额外匹配（如 id）。
     */
    data class Option(
        val key: Int?,
        val name: String,
        val badge: String? = null,
        val highlight: Boolean = false,
        val searchKey: String = "",
    )

    fun show(
        activity: Activity,
        title: String,
        emptyText: String,
        options: List<Option>,
        onPicked: (Option) -> Unit,
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_picker, null)
        val countText = view.findViewById<TextView>(R.id.text_dialog_count)
        val search = view.findViewById<EditText>(R.id.input_picker_search)
        val listView = view.findViewById<ListView>(R.id.list_picker_options)
        view.findViewById<TextView>(R.id.text_dialog_title).text = title
        view.findViewById<TextView>(R.id.text_picker_empty).text = emptyText
        listView.emptyView = view.findViewById(R.id.text_picker_empty)

        val adapter = OptionAdapter(activity)
        listView.adapter = adapter

        fun applyFilter(keyword: String) {
            val filtered = if (keyword.isBlank()) {
                options
            } else {
                options.filter {
                    it.name.contains(keyword, true) || it.searchKey.contains(keyword, true)
                }
            }
            adapter.submit(filtered)
            countText.text = "共 ${filtered.size} 个"
            setListViewRows(activity, listView, filtered.size)
        }
        applyFilter("")

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString()?.trim().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        listView.setOnItemClickListener { _, _, position, _ ->
            onPicked(adapter.getItem(position))
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    /** 列表高度随结果条数自适应：1~6 行内不滚动，超出后固定 6 行高度。 */
    private fun setListViewRows(activity: Activity, listView: ListView, count: Int) {
        val rowHeight = (52 * activity.resources.displayMetrics.density).toInt()
        val rows = if (count == 0) 2 else count.coerceIn(1, 6)
        listView.layoutParams = listView.layoutParams.apply { height = rowHeight * rows }
    }

    private class OptionAdapter(private val activity: Activity) : BaseAdapter() {

        private var items: List<Option> = emptyList()

        fun submit(newItems: List<Option>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Option = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: activity.layoutInflater.inflate(R.layout.item_picker_option, parent, false)
            val option = items[position]
            val name = view.findViewById<TextView>(R.id.text_option_name)
            name.text = option.name
            name.setTextColor(
                ContextCompat.getColor(activity, if (option.highlight) R.color.ad_primary else R.color.ad_text)
            )
            val badge = view.findViewById<TextView>(R.id.text_option_badge)
            if (option.badge.isNullOrEmpty()) {
                badge.visibility = View.GONE
            } else {
                badge.visibility = View.VISIBLE
                badge.text = option.badge
                badge.setBackgroundResource(
                    if (option.highlight) R.drawable.ad_chip_primary else R.drawable.ad_chip_idle
                )
                badge.setTextColor(
                    ContextCompat.getColor(
                        activity,
                        if (option.highlight) R.color.ad_primary else R.color.ad_text_secondary,
                    )
                )
            }
            return view
        }
    }
}
