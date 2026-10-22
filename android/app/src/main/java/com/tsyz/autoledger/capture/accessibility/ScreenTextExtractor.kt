package com.tsyz.autoledger.capture.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.ArrayDeque

/**
 * 五个抓取层：DFS 节点遍历、关键词节点搜索、事件源回溯、多窗口遍历、截图 OCR。
 * 这里实现前四层的纯文本采集，不依赖固定层级或坐标。
 */
object ScreenTextExtractor {
    private const val MAX_NODES = 500
    private const val MAX_DEPTH = 24
    private const val MAX_TEXT = 16_000

    private val paymentKeywords = listOf(
        "支付成功", "付款成功", "交易成功", "已支付", "转账", "收款", "到账",
        "消费", "扣款", "订单金额", "付款金额", "¥", "￥"
    )

    fun fromEvent(event: AccessibilityEvent): String {
        val builder = StringBuilder()
        event.text.forEach { builder.append(it).append('\n') }
        event.contentDescription?.let { builder.append(it).append('\n') }
        return builder.toString().take(MAX_TEXT)
    }

    /** 第三层：事件源 + 祖先链回溯，在事件回调内同步调用。 */
    fun fromEventSource(event: AccessibilityEvent): String {
        val node = runCatching { event.source }.getOrNull() ?: return ""
        val builder = StringBuilder()
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            val info = current
            appendNode(info, builder)
            current = runCatching { info.parent }.getOrNull()
            depth++
        }
        runCatching { node.recycle() }
        return builder.toString().take(MAX_TEXT)
    }

    /** 第一层：DFS 遍历当前窗口节点。 */
    fun dfsText(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val builder = StringBuilder()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.push(root to 0)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = stack.pop()
            visited++
            appendNode(node, builder)
            if (depth < MAX_DEPTH) {
                for (index in node.childCount - 1 downTo 0) {
                    runCatching { node.getChild(index) }.getOrNull()?.let { child ->
                        stack.push(child to depth + 1)
                    }
                }
            }
        }
        return builder.toString().take(MAX_TEXT)
    }

    /** 第二层：findAccessibilityNodeInfosByText 关键词搜索。 */
    fun keywordText(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val builder = StringBuilder()
        for (keyword in paymentKeywords) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByText(keyword) }.getOrNull() ?: continue
            for (node in nodes.take(12)) {
                appendNode(node, builder)
                runCatching { node.parent }.getOrNull()?.let { parent ->
                    appendNode(parent, builder)
                }
            }
            if (builder.length > MAX_TEXT / 2) break
        }
        return builder.toString().take(MAX_TEXT)
    }

    /** 第四层：多窗口遍历，覆盖悬浮窗、分屏、弹窗。 */
    fun windowsText(windows: List<AccessibilityWindowInfo>): String {
        val builder = StringBuilder()
        for (window in windows) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            builder.append(dfsText(root)).append('\n')
            if (builder.length > MAX_TEXT) break
        }
        return builder.toString().take(MAX_TEXT)
    }

    fun looksLikePayment(text: String): Boolean =
        com.tsyz.autoledger.core.util.PaymentTextParser.looksLikePayment(text)

    private fun appendNode(node: AccessibilityNodeInfo, builder: StringBuilder) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) builder.append(text).append('\n')
        val description = node.contentDescription?.toString()?.trim()
        if (!description.isNullOrBlank()) builder.append(description).append('\n')
        if (builder.length > MAX_TEXT) return
    }
}
