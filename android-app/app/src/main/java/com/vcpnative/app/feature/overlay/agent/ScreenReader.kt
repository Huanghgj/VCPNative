package com.vcpnative.app.feature.overlay.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class ScreenReader(private val service: AccessibilityService) {

    data class ScreenNode(
        val id: Int,
        val className: String,
        val text: String?,
        val contentDescription: String?,
        val resourceId: String?,
        val bounds: Rect,
        val isClickable: Boolean,
        val isScrollable: Boolean,
        val isEditable: Boolean,
        val isCheckable: Boolean,
        val isChecked: Boolean,
    )

    data class ScreenSnapshot(
        val packageName: String,
        val nodes: List<ScreenNode>,
        val timestamp: Long,
    )

    private companion object {
        const val MAX_NODES = 50
        const val DEDUP_DISTANCE_PX = 10
    }

    fun readScreen(): ScreenSnapshot? {
        val root = findAppRoot() ?: return null
        val packageName = root.packageName?.toString() ?: "unknown"

        val rawNodes = mutableListOf<ScreenNode>()
        val counter = intArrayOf(0)
        traverseTree(root, rawNodes, counter)
        root.recycle()

        val deduped = deduplicateNodes(rawNodes)
        val limited = if (deduped.size > MAX_NODES) {
            // Prioritize clickable and text-bearing nodes
            val clickable = deduped.filter { it.isClickable || it.isEditable }
            val rest = deduped.filter { !it.isClickable && !it.isEditable }
            val result = clickable.take(MAX_NODES).toMutableList()
            val remaining = MAX_NODES - result.size
            if (remaining > 0) result.addAll(rest.take(remaining))
            // Re-assign IDs sequentially
            result.mapIndexed { index, node -> node.copy(id = index + 1) }
        } else {
            deduped
        }

        return ScreenSnapshot(
            packageName = packageName,
            nodes = limited,
            timestamp = System.currentTimeMillis(),
        )
    }

    fun formatForLlm(snapshot: ScreenSnapshot): String {
        val sb = StringBuilder()
        sb.appendLine("当前应用: ${snapshot.packageName}")
        sb.appendLine("屏幕元素:")
        if (snapshot.nodes.isEmpty()) {
            sb.appendLine("(无可交互元素)")
            return sb.toString()
        }
        for (node in snapshot.nodes) {
            sb.append("[${node.id}] ")
            sb.append(simplifyClassName(node.className))
            val label = node.text ?: node.contentDescription
            if (label != null) sb.append(" \"$label\"")
            sb.append(" (${node.bounds.left},${node.bounds.top})-(${node.bounds.right},${node.bounds.bottom})")
            val flags = buildList {
                if (node.isClickable) add("clickable")
                if (node.isScrollable) add("scrollable")
                if (node.isEditable) add("editable")
                if (node.isCheckable) add(if (node.isChecked) "checked" else "checkable")
            }
            if (flags.isNotEmpty()) sb.append(" ${flags.joinToString(",")}")
            if (node.resourceId != null) {
                val shortId = node.resourceId.substringAfterLast("/")
                sb.append(" #$shortId")
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    fun findNodeById(snapshot: ScreenSnapshot, nodeId: Int): ScreenNode? {
        return snapshot.nodes.find { it.id == nodeId }
    }

    fun findAccessibilityNode(nodeId: Int, snapshot: ScreenSnapshot): AccessibilityNodeInfo? {
        val targetNode = snapshot.nodes.find { it.id == nodeId } ?: return null
        val root = findAppRoot() ?: return null
        return findNodeByBounds(root, targetNode.bounds)
    }

    /**
     * Find the root node of the actual app behind our overlay.
     * Skips accessibility-overlay and input-method windows so the agent
     * reads the real screen, not our own floating panel.
     */
    private fun findAppRoot(): AccessibilityNodeInfo? {
        val windows = service.windows
        if (!windows.isNullOrEmpty()) {
            val ownPackage = service.packageName
            // Prefer the topmost TYPE_APPLICATION window that isn't our own app
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val root = window.root ?: continue
                    if (root.packageName?.toString() != ownPackage) {
                        return root
                    }
                    root.recycle()
                }
            }
            // Fall back to any application window (including our own main app)
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    return window.root
                }
            }
        }
        // Last resort: rootInActiveWindow (may be the overlay itself)
        return service.rootInActiveWindow
    }

    private fun findNodeByBounds(root: AccessibilityNodeInfo, targetBounds: Rect): AccessibilityNodeInfo? {
        val nodeBounds = Rect()
        root.getBoundsInScreen(nodeBounds)
        if (nodeBounds == targetBounds) {
            val text = root.text?.toString()
            val desc = root.contentDescription?.toString()
            if (root.isClickable || root.isScrollable || root.isEditable ||
                text != null || desc != null) {
                return root
            }
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByBounds(child, targetBounds)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun traverseTree(
        node: AccessibilityNodeInfo,
        result: MutableList<ScreenNode>,
        counter: IntArray,
    ) {
        val isInteractive = node.isClickable || node.isScrollable ||
            node.isEditable || node.isCheckable
        val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()

        if (isInteractive || hasText) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            // Skip nodes with zero or negative area
            if (bounds.width() > 0 && bounds.height() > 0) {
                counter[0]++
                result.add(
                    ScreenNode(
                        id = counter[0],
                        className = node.className?.toString() ?: "View",
                        text = node.text?.toString(),
                        contentDescription = node.contentDescription?.toString(),
                        resourceId = node.viewIdResourceName,
                        bounds = bounds,
                        isClickable = node.isClickable,
                        isScrollable = node.isScrollable,
                        isEditable = node.isEditable,
                        isCheckable = node.isCheckable,
                        isChecked = node.isChecked,
                    ),
                )
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseTree(child, result, counter)
            child.recycle()
        }
    }

    private fun deduplicateNodes(nodes: List<ScreenNode>): List<ScreenNode> {
        if (nodes.size <= 1) return nodes
        val kept = mutableListOf<ScreenNode>()
        for (node in nodes) {
            val cx = node.bounds.centerX()
            val cy = node.bounds.centerY()
            val isDuplicate = kept.any { existing ->
                val dx = cx - existing.bounds.centerX()
                val dy = cy - existing.bounds.centerY()
                dx * dx + dy * dy < DEDUP_DISTANCE_PX * DEDUP_DISTANCE_PX
            }
            if (!isDuplicate) {
                kept.add(node)
            }
        }
        // Re-assign sequential IDs
        return kept.mapIndexed { index, node -> node.copy(id = index + 1) }
    }

    private fun simplifyClassName(className: String): String {
        return className.substringAfterLast(".")
    }
}
