package org.autojs.autodroid.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.DrawableRes
import org.autojs.autojs6.R

/**
 * 空态视图（iOS ContentUnavailableView 风格）：图标 + 标题 + 一行提示。
 *
 * 以**透明穿透遮罩**形式叠在 ListView 的包裹层（FrameLayout）上：列表与
 * 列表头部（搜索/新建等）保持可见可点，空态内容居中悬浮；显隐由数据层
 * 控制（加载中/出错/有数据时隐藏）。不用 ListView.emptyView——它会连
 * 列表头部一起隐藏，过滤无结果时搜索框会凭空消失。
 */
object AdEmptyState {

    fun create(
        inflater: LayoutInflater,
        title: String,
        hint: String,
        @DrawableRes iconRes: Int? = null,
    ): View {
        val view = inflater.inflate(R.layout.view_empty_state, null)
        view.findViewById<TextView>(R.id.text_empty_title).text = title
        view.findViewById<TextView>(R.id.text_empty_hint).text = hint
        if (iconRes != null) {
            view.findViewById<ImageView>(R.id.image_empty).setImageResource(iconRes)
        }
        return view
    }

    /**
     * 挂载为空态遮罩（初始隐藏），返回视图由调用方切换 visibility。
     * 父容器需为普通 ViewGroup（如 FrameLayout 包裹层）。
     */
    fun bind(
        list: ListView,
        title: String,
        hint: String,
        @DrawableRes iconRes: Int? = null,
    ): View {
        val empty = create(LayoutInflater.from(list.context), title, hint, iconRes)
        empty.isClickable = false
        empty.isFocusable = false
        empty.visibility = View.GONE
        (list.parent as? ViewGroup)?.addView(
            empty,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        return empty
    }
}
