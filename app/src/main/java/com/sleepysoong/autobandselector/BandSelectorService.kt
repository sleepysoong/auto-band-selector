package com.sleepysoong.autobandselector

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sleepysoong.autobandselector.automation.*

/** Android adapter only. It snapshots nodes and never retains AccessibilityNodeInfo. */
class BandSelectorService : AccessibilityService() {
    @Volatile private var activeRun: RuntimeBridge.RunBinding? = null
    @Volatile private var driver: SamsungMacroDriver? = null
    @Volatile private var eventIdentity: WindowIdentity? = null
    @Volatile private var executingAction: MacroAction? = null
    @Volatile private var plannedScreen: Class<out ScreenObservation>? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val run = RuntimeBridge.currentRun() ?: return detachLocal()
        if (isLocked()) {
            revokeRun()
            return
        }
        // Launching the dialer emits transient events from this app, System UI and the launcher.
        // They are not evidence that accessibility was lost. Only the allowlisted dialer root is
        // ever snapshotted or authorized to perform an action; service loss is handled by the
        // lifecycle callbacks and an unresponsive launch is bounded by the coordinator timeout.
        val eventPackage = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            (application as? BandSelectorApp)?.logs?.append("화면 전환: 패키지=$eventPackage")
        }
        if (eventPackage !in SamsungProfiles.supportedPackages) return
        eventIdentity = WindowIdentity(eventPackage, event.className?.toString().orEmpty())
        if (activeRun !== run) bind(run)
        val action = run.currentAction() ?: return
        val activeDriver = driver ?: return
        if (!run.isAuthorized(action)) {
            revokeRun()
            return
        }
        executingAction = action
        try {
            val result = activeDriver.execute(action)
            val detail = when (result) {
                is MacroResult.Advance -> "다음=${result.nextStage}"
                is MacroResult.Complete -> "검증 완료"
                is MacroResult.Failure -> "실패=${result.reason}"
            }
            (application as? BandSelectorApp)?.logs?.append("접근성 ${action.stage} [$eventPackage, ${plannedScreen?.simpleName}]: $detail")
            run.resultSink(result)
        } finally {
            executingAction = null
        }
    }

    private fun bind(run: RuntimeBridge.RunBinding) {
        activeRun = run
        driver = SamsungMacroDriver(
            parser = run.parser,
            snapshot = ::snapshotWindow,
            click = { ref -> withFreshNode(ref) { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } },
            setText = { ref, value -> withFreshNode(ref) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }
                it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } },
            isAuthorized = { action -> RuntimeBridge.currentRun() === run && run.isAuthorized(action) && !isLocked() },
            expectedSimSlot = run.expectedSimSlot,
            targetBand = run.targetBand,
            scroll = { ref, direction -> withFreshNode(ref) {
                it.performAction(if (direction == ScrollDirection.Forward)
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            } },
            canScrollBackward = { ref -> withFreshNodeValue(ref) { node ->
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }
            } },
            back = {
                if (RuntimeBridge.currentRun() !== run || isLocked()) false
                else performGlobalAction(GLOBAL_ACTION_BACK)
            }
        )
    }

    private fun snapshotWindow(): WindowSnapshot? {
        val run = activeRun ?: return null
        val expected = eventIdentity?.packageName ?: return null
        val root = rootForPackage(expected) ?: return null
        // Content-change events identify the changed child, not the window's root class.
        val identity = WindowIdentity(expected, root.className?.toString().orEmpty())
        eventIdentity = identity
        val snapshot = WindowSnapshot(identity, snapshotNode(root))
        plannedScreen = run.parser.parse(snapshot, run.expectedSimSlot).javaClass
        return snapshot
    }

    private fun snapshotNode(node: AccessibilityNodeInfo): NodeSnapshot {
        val bounds = Rect().also(node::getBoundsInScreen)
        val children = ArrayList<NodeSnapshot>(node.childCount)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> children += snapshotNode(child) }
        }
        return NodeSnapshot(
            packageName = node.packageName?.toString().orEmpty(),
            className = node.className?.toString().orEmpty(),
            viewId = node.viewIdResourceName,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            checkable = node.isCheckable,
            checked = if (node.isCheckable) legacyChecked(node) else null,
            clickable = node.isClickable,
            children = children,
            visible = node.isVisibleToUser,
            scrollable = node.isScrollable,
            canScrollForward = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD },
            bounds = NodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
        )
    }

    private inline fun withFreshNode(ref: NodeRef, block: (AccessibilityNodeInfo) -> Boolean): Boolean =
        withFreshNodeValue(ref, block) ?: false

    private fun rootForPackage(expected: String): AccessibilityNodeInfo? {
        if (expected !in SamsungProfiles.supportedPackages) return null
        val active = rootInActiveWindow
        if (active?.packageName?.toString() == expected) return active
        // Our PiP can own focus while the menu remains in an interactive window.
        if (active?.packageName?.toString() != packageName) return null
        return windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == expected }.singleOrNull()
    }

    private inline fun <T> withFreshNodeValue(ref: NodeRef, block: (AccessibilityNodeInfo) -> T): T? {
        val run = activeRun ?: return null
        val original = executingAction ?: return null
        val current = run.currentAction() ?: return revokeStaleRoot()
        val identity = eventIdentity ?: return revokeStaleRoot()
        var node = rootForPackage(identity.packageName) ?: return revokeStaleRoot()
        val freshSnapshot = WindowSnapshot(identity, snapshotNode(node))
        val freshScreen = run.parser.parse(freshSnapshot, run.expectedSimSlot)
        if (current != original || RuntimeBridge.currentRun() !== run || !run.isAuthorized(original) ||
            isLocked() || node.packageName?.toString() != identity.packageName ||
            node.className?.toString() != identity.windowClass ||
            freshScreen.javaClass != plannedScreen) return revokeStaleRoot()
        for (part in ref.path.split('/').drop(1)) {
            node = node.getChild(part.toIntOrNull() ?: return revokeStaleRoot()) ?: return revokeStaleRoot()
        }
        return block(node)
    }

    private fun <T> revokeStaleRoot(): T? {
        revokeRun()
        return null
    }


    private fun legacyChecked(node: AccessibilityNodeInfo): Boolean =
        AccessibilityNodeInfo::class.java.getMethod("isChecked").invoke(node) as Boolean

    private fun isLocked(): Boolean =
        (getSystemService(KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceLocked == true

    private fun revokeRun() {
        detachLocal()
        RuntimeBridge.revoke()
    }

    private fun detachLocal() {
        driver = null
        activeRun = null
        eventIdentity = null
        plannedScreen = null
        executingAction = null
    }

    override fun onInterrupt() = revokeRun()

    override fun onDestroy() {
        revokeRun()
        super.onDestroy()
    }
}
