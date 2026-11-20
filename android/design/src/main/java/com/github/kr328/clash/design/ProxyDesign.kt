package com.github.kr328.clash.design

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.databinding.DesignProxyBinding
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.design.util.selectableItemBackground

class ProxyDesign(
    context: Context,
    private val groupNames: List<String>,
    private val showGroupBack: (Boolean) -> Unit,
) : Design<ProxyDesign.Request>(context) {
    sealed class Request {
        object ReloadAll : Request()
        data class Select(val index: Int, val name: String) : Request()
        data class UrlTest(val index: Int, val node: String? = null) : Request()
    }

    private data class Group(val nodes: List<Proxy>, val selectable: Boolean, val state: ProxyState)

    private data class Row(
        val id: String,
        val title: String,
        val subtitle: String? = null,
        val trailing: String? = null,
        val selected: Boolean = false,
        val section: Boolean = false,
        val action: Boolean = false,
        val openGroup: Int? = null,
        val request: Request? = null,
        val test: Request.UrlTest? = null,
    )

    private val binding = DesignProxyBinding.inflate(context.layoutInflater, context.root, false)
    private val knownGroups = mutableMapOf<Int, Group>()
    private val tests = mutableMapOf<Int, String?>()
    private val tested = mutableSetOf<Pair<Int, String>>()
    private var openedGroup: Int? = null
    private val selections = mutableMapOf<Int, String>()
    private var rootSearch = ""
    private var editingSearch = false
    private var running = false
    private var currentSelection: String? = null
    private val globalIndex = groupNames.indexOf("GLOBAL")
    private val accent = context.resolveThemedColor(androidx.appcompat.R.attr.colorPrimary)
    private val textColor = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
    private val actionTextColor = context.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimary)
    private val muted = context.resolveThemedColor(R.attr.glassTextSecondary)
    private val rows = Rows()
    override val root: View = binding.root

    init {
        binding.self = this
        binding.activityBarLayout.applyFrom(context)
        binding.proxyItems.layoutManager = LinearLayoutManager(context)
        binding.proxyItems.adapter = rows
        binding.proxySearch.doAfterTextChanged { value ->
            if (!editingSearch) {
                val text = value?.toString().orEmpty()
                val index = openedGroup
                if (index == null) rootSearch = text else selections[index] = text
                render()
            }
        }
        updatePage()
    }

    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        if (!value) {
            knownGroups.clear()
            currentSelection = null
            tests.clear()
            tested.clear()
            openedGroup = null
        }
        updatePage()
    }

    fun setCurrentSelection(name: String?) {
        currentSelection = name
        render()
    }

    fun updateGroup(position: Int, proxies: List<Proxy>, selectable: Boolean, parent: ProxyState) {
        if (!running || position !in groupNames.indices) return
        knownGroups[position] = Group(proxies, selectable, parent)
        render()
    }

    fun finishUrlTesting(position: Int, completed: Boolean) {
        if (position !in tests) return
        val node = tests.remove(position)
        if (completed) {
            if (node != null) {
                tested += position to node
            } else {
                knownGroups[position]?.nodes.orEmpty().forEach { proxy ->
                    if (!proxy.isGroup) tested += position to proxy.name
                }
            }
        }
        render()
    }

    fun closeGroup(): Boolean {
        if (openedGroup == null) return false
        openedGroup = null
        updatePage()
        return true
    }

    private fun openGroup(index: Int) {
        openedGroup = index
        updatePage()
    }

    private fun updatePage() {
        val group = openedGroup
        showGroupBack(group != null)
        binding.activityBarLayout.findViewById<TextView>(R.id.activity_bar_title_view).text =
            group?.let(groupNames::get) ?: context.getString(R.string.roytools_proxy_nodes)
        binding.proxySearch.hint = if (group == null) "搜索代理组和节点" else "搜索组内节点"
        binding.proxySearch.visibility = if (running) View.VISIBLE else View.GONE
        editingSearch = true
        binding.proxySearch.setText(if (group == null) rootSearch else selections[group].orEmpty())
        editingSearch = false
        render()
        binding.proxyItems.scrollToPosition(0)
    }

    private fun execute(request: Request) {
        if (!running) return
        if (request is Request.UrlTest) {
            if (request.index in tests) return
            tests[request.index] = request.node
            render()
        }
        requests.trySend(request)
    }

    private fun showNodeMenu(anchor: View, test: Request.UrlTest) {
        PopupMenu(context, anchor, Gravity.END, 0, R.style.RoyTools_AnchoredMenu).apply {
            menu.add(R.string.roytools_proxy_test_latency)
                .setEnabled(test.index !in tests)
                .setOnMenuItemClickListener {
                    execute(test)
                    true
                }
            show()
        }
    }

    private fun render() {
        val openIndex = openedGroup
        val query = if (openIndex == null) rootSearch else selections[openIndex].orEmpty()
        val search = query.trim()
        val global = knownGroups[globalIndex]
        val selection = currentSelection.takeUnless { it.isNullOrBlank() } ?: global?.state?.now?.takeUnless { it == "?" }
        val result = buildList {
            if (!running) {
                add(Row("offline", "连接VPN后可选择代理节点和测试延迟。"))
            } else if (openIndex == null) {
                add(Row("selection-header", "代理选择", section = true))
                add(Row("selection", "当前选择", trailing = selection ?: "等待节点"))
                val groups = groupNames.indices.filter { it != globalIndex }
                if (groups.isNotEmpty()) {
                    add(Row("groups-header", "代理组", section = true))
                    for (index in groups) {
                        val name = groupNames[index]
                        val picked = knownGroups[index]?.state?.now?.takeUnless { it.isBlank() || it == "?" }
                        if (name.contains(search, true) || picked?.contains(search, true) == true) {
                            add(Row("group:$index", name, subtitle = picked, selected = selection == name, openGroup = index))
                        }
                    }
                    if (none { it.openGroup != null }) add(Row("groups-empty", "没有匹配的代理组"))
                }
                val nodes = global?.nodes.orEmpty().filter { !it.isGroup && it.name !in builtInRoutes }
                if (nodes.isNotEmpty()) appendNodes(globalIndex, nodes, global?.selectable == true, selection, search, "节点")
                else if (groups.isEmpty()) add(Row("empty", if (global == null) "正在读取代理节点…" else "当前配置没有可用的代理节点。"))
            } else {
                val group = knownGroups[openIndex]
                val groupName = groupNames[openIndex]
                add(Row("group-selection-header", "代理选择", section = true))
                if (selection == groupName) {
                    add(Row("group-selection", "当前选用的代理组", selected = true))
                } else if (globalIndex >= 0) {
                    add(Row("group-selection", "选用此代理组", action = true, request = Request.Select(globalIndex, groupName)))
                }
                if (group?.selectable == false) add(Row("automatic", "此组由配置自动选择节点。"))
                if (group == null) add(Row("loading", "正在读取组内节点…"))
                else appendNodes(openIndex, group.nodes, group.selectable, group.state.now, search, "组内选择")
            }
        }
        rows.submitList(result)
    }

    private fun MutableList<Row>.appendNodes(
        index: Int, nodes: List<Proxy>, selectable: Boolean, selection: String?, search: String, title: String,
    ) {
        add(Row("nodes-header", title, section = true))
        val groupTesting = index in tests && tests[index] == null
        if (nodes.isNotEmpty()) {
            add(Row("batch", if (groupTesting) "正在批量测试延迟…" else "批量测试延迟",
                action = true, request = Request.UrlTest(index).takeUnless { index in tests }))
        }
        val matches = nodes.filter { it.name.contains(search, true) || it.title.contains(search, true) }
        if (matches.isEmpty()) add(Row("nodes-empty", if (nodes.isEmpty()) "暂无可用节点" else "没有匹配的节点"))
        for (node in matches) {
            val end = when {
                groupTesting || tests[index] == node.name -> "测试中"
                node.delay in 1..65534 -> "${node.delay}ms"
                (index to node.name) in tested -> "失败"
                else -> null
            }
            val selected = selection == node.name
            add(Row("node:${node.name}", node.title.ifBlank { node.name },
                trailing = end, selected = selected,
                request = Request.Select(index, node.name).takeIf { selectable && !selected },
                test = Request.UrlTest(index, node.name).takeUnless { node.isGroup }))
        }
    }

    private inner class Rows : ListAdapter<Row, RowView>(object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(old: Row, new: Row) = old.id == new.id
        override fun areContentsTheSame(old: Row, new: Row) = old == new
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = RowView()
        override fun onBindViewHolder(holder: RowView, position: Int) = holder.bind(getItem(position))
    }

    private inner class RowView : RecyclerView.ViewHolder(LinearLayout(context)) {
        private val line = itemView as LinearLayout
        private val title = TextView(context)
        private val subtitle = TextView(context)
        private val trailing = TextView(context).apply {
            includeFontPadding = false
            textSize = 14f
            maxLines = 1
            maxWidth = dp(160)
            ellipsize = TextUtils.TruncateAt.END
        }
        private val selected = indicator(R.drawable.ic_outline_check_circle).apply {
            imageTintList = ColorStateList.valueOf(accent)
        }
        private val disclosure = indicator(R.drawable.ic_glass_chevron)
        private val overflow = ImageButton(context).apply {
            setImageResource(R.drawable.ic_baseline_more_vert)
            imageTintList = ColorStateList.valueOf(muted)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setBackgroundResource(R.drawable.bg_glass_clickable)
            setPadding(0, 0, 0, 0)
            isFocusable = true
        }
        private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        private fun indicator(drawable: Int) = ImageView(context).apply {
            setImageResource(drawable)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init {
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            line.isBaselineAligned = false
            line.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
            line.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(subtitle)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(trailing, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            line.addView(selected, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(6) })
            line.addView(disclosure, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(4) })
            val touchTarget = context.resources.getDimensionPixelSize(R.dimen.glass_touch_target)
            line.addView(overflow, LinearLayout.LayoutParams(touchTarget, touchTarget).apply { marginStart = dp(4) })
            title.ellipsize = TextUtils.TruncateAt.END
            title.maxLines = 2
            subtitle.textSize = 13f
            subtitle.setTextColor(muted)
            subtitle.maxLines = 1
            subtitle.ellipsize = TextUtils.TruncateAt.END
        }

        fun bind(row: Row) {
            line.minimumHeight = dp(if (row.section) 40 else 52)
            val paddingTop = when {
                row.section -> 12
                row.test != null -> 3
                else -> 9
            }
            line.setPaddingRelative(
                dp(14), dp(paddingTop), dp(if (row.test != null) 8 else 14), dp(if (row.test != null) 3 else 9),
            )
            val clickable = row.openGroup != null || row.request != null
            when {
                row.section -> line.background = null
                row.action -> line.setBackgroundResource(R.drawable.roytools_proxy_action)
                else -> line.setBackgroundResource(R.drawable.roytools_card)
            }
            line.foreground = if (clickable && !row.action) context.selectableItemBackground else null
            line.isEnabled = !row.action || clickable
            line.alpha = if (line.isEnabled) 1f else 0.55f
            line.clipToOutline = !row.section
            title.text = row.title
            title.textSize = if (row.section) 13f else 16f
            title.setTextColor(when {
                row.section -> muted
                row.action -> actionTextColor
                else -> textColor
            })
            subtitle.text = row.subtitle
            subtitle.visibility = if (row.subtitle == null) View.GONE else View.VISIBLE
            trailing.text = row.trailing
            trailing.visibility = if (row.trailing.isNullOrEmpty()) View.GONE else View.VISIBLE
            trailing.setTextColor(if (row.selected) accent else muted)
            selected.visibility = if (row.selected) View.VISIBLE else View.GONE
            disclosure.visibility = if (row.openGroup != null) View.VISIBLE else View.GONE
            val test = row.test
            overflow.visibility = if (test == null) View.GONE else View.VISIBLE
            overflow.contentDescription = test?.let { context.getString(R.string.roytools_proxy_node_more, row.title) }
            overflow.setOnClickListener(test?.let { request ->
                View.OnClickListener { showNodeMenu(overflow, request) }
            })
            line.setOnClickListener(if (clickable) View.OnClickListener {
                if (row.openGroup != null) openGroup(row.openGroup)
                else row.request?.let(::execute)
            } else null)
            line.isClickable = clickable
            line.isFocusable = clickable
            line.isSelected = row.selected
            ViewCompat.setAccessibilityHeading(line, row.section)
        }
    }

    private companion object {
        val builtInRoutes = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE")
    }
}
