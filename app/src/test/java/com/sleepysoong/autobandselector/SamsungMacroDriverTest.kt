package com.sleepysoong.autobandselector

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sleepysoong.autobandselector.automation.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService

/** Synthetic framework trees, never a physical Samsung compatibility claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [SamsungMacroDriverTest.RootShadow::class])
class SamsungMacroDriverTest {
    @Implements(AccessibilityService::class)
    class RootShadow : ShadowAccessibilityService() {
        @Implementation fun getRootInActiveWindow(): AccessibilityNodeInfo? =
            if (roots.isEmpty()) currentRoot else roots.removeFirst()
        companion object {
            var currentRoot: AccessibilityNodeInfo? = null
            val roots = ArrayDeque<AccessibilityNodeInfo>()
        }
    }

    @After fun cleanup() {
        RootShadow.currentRoot = null
        RootShadow.roots.clear()
        RuntimeBridge.detach()
    }

    private val identity = WindowIdentity(SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE, "Synthetic")
    private val ids = ScreenFieldIds("title", "digits", "password", "rat", "band", "sim")
    private val parser = SamsungScreenParser(listOf(ScreenProfile(identity, ScreenKind.entries.toSet(), ids)))
    private val approvedComponent = ComponentName(SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE,
        "com.samsung.android.dialer.DialtactsActivity")
    private fun resolver(vararg matches: ResolvedPhoneActivity) =
        SamsungPhoneActivityResolver { matches.toList() }
    private fun approvedResolver() = resolver(ResolvedPhoneActivity(approvedComponent, exported = true, enabled = true))

    private fun n(text: String? = null, id: String? = null, checkable: Boolean = false,
                  checked: Boolean? = null, clickable: Boolean = false,
                  children: List<NodeSnapshot> = emptyList(), scrollable: Boolean = false,
                  forward: Boolean? = null) = NodeSnapshot(
        identity.packageName, "View", id, text, checkable = checkable, checked = checked,
        clickable = clickable, children = children, scrollable = scrollable,
        canScrollForward = forward)

    private fun screen(title: String, vararg children: NodeSnapshot) = WindowSnapshot(
        identity, n(children = listOf(n(title, "title")) + children.toList()))

    private fun row(label: String, checked: Boolean) = n(checkable = true, checked = checked,
        clickable = true, children = listOf(n(label)))

    private val backwardBySnapshot = java.util.IdentityHashMap<WindowSnapshot, Boolean>()

    private fun bandPage(selection: Boolean, rows: List<Pair<Int, Boolean>>,
                         forward: Boolean, backward: Boolean): WindowSnapshot = screen(
        "Band Selection", row("SELECTION", selection),
        n(id = "list", scrollable = true, forward = forward,
            children = rows.map { row("LTE B${it.first}", it.second) })).also {
                backwardBySnapshot[it] = backward
            }

    private fun action(stage: MacroStage, runId: RunId = RunId(java.util.UUID.randomUUID()), attempt: Long = 1) =
        MacroAction(runId, AttemptId(attempt), RunMode.Scan, stage)

    private fun driver(snapshots: ArrayDeque<WindowSnapshot>, target: Int = 1,
                       trace: MutableList<String> = mutableListOf(),
                       authorized: (MacroAction) -> Boolean = { true }): SamsungMacroDriver {
        var current: WindowSnapshot? = null
        return SamsungMacroDriver(
        parser, { snapshots.removeFirstOrNull()?.also { current = it } },
        { trace += "click:${it.path}"; true },
        { ref, value -> trace += "text:${ref.path}:$value"; true },
        authorized, 1, target,
        scroll = { ref, direction -> trace += "scroll:${direction.name}:${ref.path}"; true },
        canScrollBackward = { backwardBySnapshot[current] })
    }

    @Test fun samsungPhoneEntryAcceptsOneExportedEnabledAllowlistedResolution() {
        var query: Intent? = null
        val request = SamsungPhoneEntry.dialIntent(SamsungPhoneActivityResolver {
            query = it
            listOf(ResolvedPhoneActivity(approvedComponent, exported = true, enabled = true))
        })
        assertEquals(Intent.ACTION_DIAL, query?.action)
        assertEquals("tel:319712358", query?.dataString)
        assertEquals(SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE, query?.`package`)
        assertEquals(Intent.ACTION_DIAL, request?.action)
        assertEquals("tel:319712358", request?.dataString)
        assertEquals(approvedComponent, request?.component)
    }

    @Test fun samsungPhoneEntryFailsClosedWhenResolutionIsAbsent() {
        assertNull(SamsungPhoneEntry.dialIntent(resolver()))
    }

    @Test fun samsungPhoneEntryFailsClosedWhenResolutionIsAmbiguous() {
        assertNull(SamsungPhoneEntry.dialIntent(resolver(
            ResolvedPhoneActivity(approvedComponent, true, true),
            ResolvedPhoneActivity(approvedComponent, true, true))))
    }

    @Test fun samsungPhoneEntryFailsClosedForSpoofedOrUnexportedResolution() {
        assertNull(SamsungPhoneEntry.dialIntent(resolver(
            ResolvedPhoneActivity(ComponentName(SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE,
                "attacker.FakeActivity"), true, true))))
        assertNull(SamsungPhoneEntry.dialIntent(resolver(
            ResolvedPhoneActivity(approvedComponent, exported = false, enabled = true))))
        assertNull(SamsungPhoneEntry.dialIntent(resolver(
            ResolvedPhoneActivity(approvedComponent, exported = true, enabled = false))))
    }

    @Test fun productionBridgeReturnsOnlyResolvedSamsungActionDialAndServiceConsumesItWithoutManualAttach() {
        val current = action(MacroStage.EnterMenu)
        val results = mutableListOf<MacroResult>()
        val binding = RuntimeBridge.RunBinding(parser, { current }, { results += it }, { it == current },
            SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE, 1, 1, {})
        assertNull(RuntimeBridge.installRun(binding, resolver()))
        assertNull(RuntimeBridge.currentRun())

        val request = RuntimeBridge.installRun(binding, approvedResolver())
        assertEquals(Intent.ACTION_DIAL, request?.action)
        assertEquals("tel:319712358", request?.dataString)
        assertEquals(approvedComponent, request?.component)

        val controller = Robolectric.buildService(BandSelectorService::class.java).create()
        val event = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = identity.packageName
            className = identity.windowClass
        }
        try {
            controller.get().onAccessibilityEvent(event)
            assertEquals(1, results.size)
            assertTrue(results.single() is MacroResult.Failure)
        } finally {
            controller.destroy()
        }
    }

    @Test fun staleOwnerCannotDetachNewerRuntimeBinding() {
        val actionA = action(MacroStage.EnterMenu)
        val actionB = action(MacroStage.EnterMenu)
        val bindingA = RuntimeBridge.RunBinding(parser, { actionA }, {}, { it == actionA },
            SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE, 1, 1, {})
        val bindingB = RuntimeBridge.RunBinding(parser, { actionB }, {}, { it == actionB },
            SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE, 1, 3, {})
        assertNotNull(RuntimeBridge.installRun(bindingA, approvedResolver()))
        assertNotNull(RuntimeBridge.installRun(bindingB, approvedResolver()))

        RuntimeBridge.detach(bindingA)

        assertSame(bindingB, RuntimeBridge.currentRun())
    }

    @Test fun runCoordinatorFactorySuppliesProductionAuthorizationActionAndResultSink() = runTest {
        val coordinator = RunCoordinator(this, effect = { awaitCancellation() })
        assertTrue(coordinator.start(RunMode.Scan) is StartResult.Started)
        val binding = RuntimeBridge.RunBinding.fromCoordinator(coordinator, parser, 1, 1)
        val request = RuntimeBridge.installRun(binding, approvedResolver())
        assertNotNull(request)

        val controller = Robolectric.buildService(BandSelectorService::class.java).create()
        val event = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = identity.packageName
            className = identity.windowClass
        }
        try {
            controller.get().onAccessibilityEvent(event)
            assertTrue(coordinator.state.value is MacroState.Failed)
        } finally {
            controller.destroy()
        }
    }

    @Test fun finalEightUsesOnlyASecondFreshObservedOwnerNode() {
        fun dialer(digits: String, spacer: Boolean) = screen("Phone", n(digits, "digits"),
            *(if (spacer) arrayOf(n("spacer"), n("8", clickable = true)) else arrayOf(n("8", clickable = true))))
        val trace = mutableListOf<String>()
        val run = action(MacroStage.EnterMenu)
        val d = driver(ArrayDeque(listOf(dialer("", false), dialer("31971235", true))), trace = trace)
        assertTrue(d.execute(run) is MacroResult.Advance)
        assertEquals(listOf("text:0/1:31971235"), trace)
        assertTrue(d.execute(run.copy(attemptId = AttemptId(2))) is MacroResult.Advance)
        assertEquals(listOf("text:0/1:31971235", "click:0/3"), trace)
    }

    @Test fun prefixNotSetByThisRunCannotAuthorizeFinalEight() {
        val page = screen("Phone", n("31971235", "digits"), n("8", clickable = true))
        val trace = mutableListOf<String>()
        driver(ArrayDeque(listOf(page)), trace = trace).execute(action(MacroStage.EnterMenu))
        assertEquals(listOf("text:0/1:31971235"), trace)
    }

    @Test fun verifiedRouteFollowsOnlyProductionTransitionsFromAuthorizedEntry() {
        fun dialer(digits: String) = screen("Phone", n(digits, "digits"), n("8", clickable = true))
        val passwordEmpty = screen("Password", n("", "password"), n("OK", clickable = true))
        val passwordFilled = screen("Password", n("774632", "password"), n("OK", clickable = true))
        val warning = screen("Warning", n("OK", clickable = true))
        val sim = screen("SIM Selection", n("SIM 1", clickable = true), n("SIM 2", clickable = true))
        val settings = screen("Network Settings", n("Network mode", clickable = true))
        val mode = screen("Network mode", row("Automatic", true), n("More options", clickable = true))
        val overflow = screen("More options", n("Band Selection", clickable = true))
        val band = bandPage(false, listOf(1 to false), false, false)
        val trace = mutableListOf<String>()
        val runId = RunId(java.util.UUID.randomUUID())
        val d = driver(ArrayDeque(listOf(dialer(""), dialer("31971235"), passwordEmpty,
            passwordFilled, warning, sim, settings, mode, overflow, band)), trace = trace)
        var stage = MacroStage.EnterMenu
        repeat(10) { index ->
            val result = d.execute(action(stage, runId, index + 1L))
            assertTrue("route failed at $stage: $result", result is MacroResult.Advance)
            stage = (result as MacroResult.Advance).nextStage
        }
        assertEquals(MacroStage.ConfigureCandidate, stage)
        assertEquals(listOf("text:0/1:31971235", "click:0/2", "text:0/1:774632",
            "click:0/2", "click:0/1", "click:0/1", "click:0/1", "click:0/2", "click:0/1"), trace)
    }

    @Test fun verifiedRouteSkipsOptionalSimWhenNetworkSettingsAppears() {
        fun dialer(digits: String) = screen("Phone", n(digits, "digits"), n("8", clickable = true))
        val settings = screen("Network Settings", n("Network mode", clickable = true))
        val mode = screen("Network mode", row("Automatic", true), n("More options", clickable = true))
        val overflow = screen("More options", n("Band Selection", clickable = true))
        val snapshots = ArrayDeque(listOf(dialer(""), dialer("31971235"),
            screen("Password", n("", "password"), n("OK", clickable = true)),
            screen("Password", n("774632", "password"), n("OK", clickable = true)),
            settings, settings, mode, overflow, bandPage(false, listOf(1 to false), false, false)))
        val d = driver(snapshots)
        val runId = RunId(java.util.UUID.randomUUID())
        var stage = MacroStage.EnterMenu
        repeat(9) { index -> stage = (d.execute(action(stage, runId, index + 1L)) as MacroResult.Advance).nextStage }
        assertEquals(MacroStage.ConfigureCandidate, stage)
    }

    @Test fun completeTwoPageMutationThenDiscardAndFreshlyRetraverse() {
        val snapshots = ArrayDeque(listOf(
            bandPage(false, listOf(1 to false, 18 to true), true, false),
            bandPage(false, listOf(1 to false, 18 to false), true, false),
            bandPage(false, listOf(1 to true, 18 to false), true, false),
            bandPage(false, listOf(3 to true, 8 to false), false, true),
            bandPage(false, listOf(3 to false, 8 to false), false, true),
            bandPage(true, listOf(3 to false, 8 to false), false, true),
            bandPage(true, listOf(1 to true, 18 to false), true, false),
            bandPage(true, listOf(1 to true, 18 to false), true, false),
            bandPage(true, listOf(3 to false, 8 to false), false, true)
        ))
        val trace = mutableListOf<String>()
        val runId = RunId(java.util.UUID.randomUUID())
        val d = driver(snapshots, trace = trace)
        var result: MacroResult = MacroResult.Failure(action(MacroStage.ConfigureCandidate, runId), FailureReason.Timeout)
        repeat(9) { i -> result = d.execute(action(MacroStage.ConfigureCandidate, runId, i + 1L)) }
        assertEquals(MacroStage.VerifyCandidate, (result as MacroResult.Advance).nextStage)
        assertEquals(listOf(
            "click:0/2/1", "click:0/2/0", "scroll:Forward:0/2", "click:0/2/0",
            "click:0/1", "scroll:Backward:0/2", "scroll:Forward:0/2"
        ), trace)
    }

    @Test fun eachKtCandidateCanBecomeTheOnlySelectedBand() {
        for (target in listOf(1, 3, 8)) {
            val initial = listOf(1, 3, 8).map { it to (it != target) }
            val afterClear1 = initial.toMutableList().also { it[it.indexOfFirst { p -> p.second }] = it.first { p -> p.second }.first to false }
            val afterClear2 = afterClear1.map { (band, selected) -> band to (selected && band == target) }
            val targetOn = afterClear2.map { (band, _) -> band to (band == target) }
            val pages = ArrayDeque(listOf(
                bandPage(false, initial, false, false),
                bandPage(false, afterClear1, false, false),
                bandPage(false, afterClear2, false, false),
                bandPage(false, targetOn, false, false),
                bandPage(true, targetOn, false, false),
                bandPage(true, targetOn, false, false)
            ))
            val runId = RunId(java.util.UUID.randomUUID())
            val d = driver(pages, target)
            var last: MacroResult? = null
            repeat(6) { last = d.execute(action(MacroStage.ConfigureCandidate, runId, it + 1L)) }
            assertEquals("B$target", MacroStage.VerifyCandidate, (last as MacroResult.Advance).nextStage)
        }
    }

    @Test fun alreadyExactDuplicateEventsAndFailedClicksNeverInvertState() {
        val exact = bandPage(true, listOf(1 to true, 3 to false, 8 to false), false, false)
        val trace = mutableListOf<String>()
        val runId = RunId(java.util.UUID.randomUUID())
        val d = driver(ArrayDeque(listOf(exact, exact)), trace = trace)
        assertTrue(d.execute(action(MacroStage.ConfigureCandidate, runId, 1)) is MacroResult.Advance)
        assertEquals(MacroStage.VerifyCandidate,
            (d.execute(action(MacroStage.ConfigureCandidate, runId, 2)) as MacroResult.Advance).nextStage)
        assertTrue(trace.isEmpty())

        val needsClick = bandPage(true, listOf(1 to false), false, false)
        val failed = SamsungMacroDriver(parser, { needsClick }, { false }, { _, _ -> false },
            { true }, 1, 1, scroll = { _, _ -> false }, canScrollBackward = { false })
            .execute(action(MacroStage.ConfigureCandidate))
        assertTrue(failed is MacroResult.Failure)
    }

    @Test fun missingResolvedSimIsTypedFailureWithZeroActions() {
        val sim = screen("SIM Selection", n("SIM 1", clickable = true))
        val trace = mutableListOf<String>()
        val result = SamsungMacroDriver(parser, { sim }, { trace += "click"; true },
            { _, _ -> trace += "text"; true }, { true }, 2, 1)
            .execute(action(MacroStage.ChooseSimIfShown))
        assertTrue(result is MacroResult.Failure)
        assertTrue(trace.isEmpty())
    }

    @Test fun unreadableAndMissingTargetsAreTypedFailures() {
        val unreadable = screen("Band Selection", row("SELECTION", false),
            n(id = "list", scrollable = true, forward = false,
                children = listOf(n(children = listOf(n("LTE B1"))))))
        assertTrue(driver(ArrayDeque(listOf(unreadable))).execute(action(MacroStage.ConfigureCandidate)) is MacroResult.Failure)
        val missing = bandPage(false, listOf(18 to false), false, false)
        assertTrue(driver(ArrayDeque(listOf(missing))).execute(action(MacroStage.ConfigureCandidate)) is MacroResult.Failure)
    }

    @Test fun manyMutationsOnOnePageDoNotConsumeTwentyPageTraversalBudget() {
        val selected = (21..45).map { it to true } + (1 to false)
        val snapshots = mutableListOf<WindowSnapshot>()
        var state = selected
        snapshots += bandPage(false, state, true, false)
        for (band in 21..45) {
            state = state.map { if (it.first == band) it.first to false else it }
            snapshots += bandPage(false, state, true, false)
        }
        state = state.map { if (it.first == 1) 1 to true else it }
        snapshots += bandPage(false, state, true, false)
        snapshots += bandPage(false, listOf(50 to false), false, true)
        snapshots += bandPage(true, listOf(50 to false), false, true)
        snapshots += bandPage(true, state, true, false)
        snapshots += bandPage(true, state, true, false)
        snapshots += bandPage(true, listOf(50 to false), false, true)
        val d = driver(ArrayDeque(snapshots))
        val runId = RunId(java.util.UUID.randomUUID())
        var result: MacroResult? = null
        snapshots.indices.forEach { result = d.execute(action(MacroStage.ConfigureCandidate, runId, it + 1L)) }
        assertEquals(MacroStage.VerifyCandidate, (result as MacroResult.Advance).nextStage)
    }

    @Test fun twentyDistinctPagesAreAllowedButTwentyFirstDistinctPageFails() {
        val pages = ArrayDeque((1..21).map { index ->
            bandPage(false, listOf((index + 20) to false), index < 21, index > 1)
        })
        val runId = RunId(java.util.UUID.randomUUID())
        val d = driver(pages, target = 1)
        var last: MacroResult? = null
        repeat(20) { last = d.execute(action(MacroStage.ConfigureCandidate, runId, it + 1L)) }
        assertTrue(last is MacroResult.Advance)
        last = d.execute(action(MacroStage.ConfigureCandidate, runId, 21))
        assertTrue(last is MacroResult.Failure)
        assertTrue(((last as MacroResult.Failure).reason as FailureReason.Rejected).reason.contains("page limit"))
    }

    @Test fun registeredReadbackStageEntersExactServiceCodeFromObservedDialerField() {
        val dialer = screen("Phone", n("", "digits"), n("8", clickable = true))
        val trace = mutableListOf<String>()
        val result = driver(ArrayDeque(listOf(dialer)), trace = trace)
            .execute(action(MacroStage.VerifyRegisteredBand))
        assertTrue(result is MacroResult.Advance)
        assertEquals(listOf("text:0/1:*123456#"), trace)
    }

    @Test fun registeredServiceModeRequiresExactTargetAndResolvedSim() {
        fun registered(band: String, sim: String = "SIM 1") =
            screen("ServiceMode", n("LTE", "rat"), n(band, "band"), n(sim, "sim"))
        val stage = action(MacroStage.VerifyRegisteredBand)
        assertTrue(driver(ArrayDeque(listOf(registered("LTE B18")))).execute(stage) is MacroResult.Failure)
        val exact = driver(ArrayDeque(listOf(registered("LTE B1")))).execute(stage)
        assertEquals(MacroStage.MeasureCandidate, (exact as MacroResult.Advance).nextStage)
        assertTrue(driver(ArrayDeque(listOf(registered("LTE B1", "SIM 2")))).execute(stage) is MacroResult.Failure)
    }

    @Test fun restoreOrdersFreshSelectionOffAutomaticClickAndLaterAutomaticVerification() {
        val selectionOn = bandPage(true, listOf(1 to true), false, false)
        val selectionOff = bandPage(false, listOf(1 to true), false, false)
        val settings = screen("Network Settings", n("Network mode", clickable = true))
        val automaticOff = screen("Network mode", row("Automatic", false))
        val automaticOn = screen("Network mode", row("Automatic", true))
        val trace = mutableListOf<String>()
        val runId = RunId(java.util.UUID.randomUUID())
        val d = driver(ArrayDeque(listOf(selectionOn, selectionOff, settings, automaticOff, automaticOn, automaticOn)), trace = trace)
        assertEquals(MacroStage.DisableSelection, (d.execute(action(MacroStage.DisableSelection, runId, 1)) as MacroResult.Advance).nextStage)
        assertEquals(MacroStage.NetworkModeAutomatic, (d.execute(action(MacroStage.DisableSelection, runId, 2)) as MacroResult.Advance).nextStage)
        assertEquals(MacroStage.NetworkModeAutomatic, (d.execute(action(MacroStage.NetworkModeAutomatic, runId, 3)) as MacroResult.Advance).nextStage)
        assertEquals(MacroStage.NetworkModeAutomatic, (d.execute(action(MacroStage.NetworkModeAutomatic, runId, 4)) as MacroResult.Advance).nextStage)
        assertEquals(MacroStage.VerifyAutomatic, (d.execute(action(MacroStage.NetworkModeAutomatic, runId, 5)) as MacroResult.Advance).nextStage)
        assertTrue(d.execute(action(MacroStage.VerifyAutomatic, runId, 6)) is MacroResult.Complete)
        assertEquals(listOf("click:0/1", "click:0/1", "click:0/1"), trace)
    }

    @Test fun staleIdentityBetweenObservationAndEffectPreventsSideEffect() {
        var checks = 0
        val trace = mutableListOf<String>()
        val page = bandPage(true, listOf(1 to false), false, false)
        val result = driver(ArrayDeque(listOf(page)), trace = trace, authorized = { ++checks == 1 })
            .execute(action(MacroStage.ConfigureCandidate))
        assertTrue(result is MacroResult.Failure)
        assertTrue(trace.isEmpty())
    }

    private fun accessibilityNode(packageName: String, className: String = "View", text: String? = null,
                                  id: String? = null, checkable: Boolean = false, checked: Boolean = false,
                                  clickable: Boolean = false, scrollable: Boolean = false,
                                  children: List<AccessibilityNodeInfo> = emptyList()): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            this.packageName = packageName
            this.className = className
            this.text = text
            viewIdResourceName = id
            isCheckable = checkable
            isChecked = checked
            isClickable = clickable
            isScrollable = scrollable
            isVisibleToUser = true
            if (scrollable) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            children.forEach { shadowOf(this).addChild(it) }
        }

    private fun performedActions(root: AccessibilityNodeInfo): List<Int> = buildList {
        addAll(shadowOf(root).performedActions)
        for (index in 0 until root.childCount) root.getChild(index)?.let { addAll(performedActions(it)) }
    }

    private fun accessibilityBandRoot(packageName: String): AccessibilityNodeInfo {
        val selection = accessibilityNode(packageName, checkable = true, checked = true,
            clickable = true, children = listOf(accessibilityNode(packageName, text = "SELECTION")))
        val target = accessibilityNode(packageName, checkable = true, checked = false,
            clickable = true, children = listOf(accessibilityNode(packageName, text = "LTE B1")))
        val list = accessibilityNode(packageName, id = "list", scrollable = true, children = listOf(target))
        return accessibilityNode(packageName, className = identity.windowClass,
            children = listOf(accessibilityNode(packageName, text = "Band Selection", id = "title"), selection, list))
    }

    @Test fun rootPackageReplacementAfterPlanRevokesAndPerformsZeroSideEffects() {
        val current = action(MacroStage.ConfigureCandidate)
        val results = mutableListOf<MacroResult>()
        var revoked = false
        RuntimeBridge.installRun(RuntimeBridge.RunBinding(parser, { current }, { results += it }, { true },
            SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE, 1, 1, { revoked = true }), approvedResolver())
        val planned = accessibilityBandRoot(identity.packageName)
        val stillCurrent = accessibilityBandRoot(identity.packageName)
        val replaced = accessibilityBandRoot("attacker.package")
        val controller = Robolectric.buildService(BandSelectorService::class.java).create()
        RootShadow.roots.addAll(listOf(planned, stillCurrent, replaced))
        val event = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = identity.packageName
            className = identity.windowClass
        }
        try {
            controller.get().onAccessibilityEvent(event)
            assertTrue(revoked)
            assertEquals(1, results.size)
            assertTrue(results.single() is MacroResult.Failure)
            assertTrue(performedActions(planned).isEmpty())
            assertTrue(performedActions(stillCurrent).isEmpty())
            assertTrue(performedActions(replaced).isEmpty())
        } finally {
            controller.destroy()
        }
    }

    @Test fun packageChangeInterruptAndServiceLossRevokeAndStopDispatch() {
        fun installed(revocations: MutableList<String>, results: MutableList<MacroResult>, current: MacroAction) {
            RuntimeBridge.installRun(RuntimeBridge.RunBinding(parser, { current }, { results += it }, { it == current },
                SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE, 1, 1, { revocations += "revoked" }),
                approvedResolver())
        }
        val current = action(MacroStage.EnterMenu)
        val revocations = mutableListOf<String>()
        val results = mutableListOf<MacroResult>()
        val controller = Robolectric.buildService(BandSelectorService::class.java).create()
        val wrong = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = "other.app" }
        try {
            installed(revocations, results, current)
            controller.get().onAccessibilityEvent(wrong)
            assertEquals(listOf("revoked"), revocations)
            assertNull(RuntimeBridge.currentRun())
            controller.get().onAccessibilityEvent(wrong)
            assertTrue(results.isEmpty())

            installed(revocations, results, current)
            controller.get().onInterrupt()
            assertEquals(2, revocations.size)
            assertNull(RuntimeBridge.currentRun())

            installed(revocations, results, current)
            controller.destroy()
            assertEquals(3, revocations.size)
            assertNull(RuntimeBridge.currentRun())
        } finally {
        }
    }

    @Test fun lockedDeviceRevokesBeforeAnyDispatch() {
        val current = action(MacroStage.EnterMenu)
        val results = mutableListOf<MacroResult>()
        var revoked = false
        RuntimeBridge.installRun(RuntimeBridge.RunBinding(parser, { current }, { results += it }, { true },
            SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE, 1, 1, { revoked = true }),
            approvedResolver())
        val controller = Robolectric.buildService(BandSelectorService::class.java).create()
        shadowOf(controller.get().getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager)
            .setIsDeviceLocked(true)
        val event = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = identity.packageName
        }
        try {
            controller.get().onAccessibilityEvent(event)
            assertTrue(revoked)
            assertTrue(results.isEmpty())
        } finally {
            controller.destroy()
        }
    }

    @Test fun persistedFlagsAndPasswordLikeUnknownWindowsNeverAct() {
        val controller = Robolectric.buildService(BandSelectorService::class.java).create()
        val prefs = controller.get().getSharedPreferences("BandSelectorPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("macro_mode", "SCANNING").putString("target_band_to_set", "LTE B1").commit()
        val event = AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply { packageName = "other.app" }
        try {
            controller.get().onAccessibilityEvent(event)
            assertNull(RuntimeBridge.currentRun())
            val unrelated = WindowSnapshot(WindowIdentity("other.app", identity.windowClass),
                n(children = listOf(n("Password", "title"), n("", "password"), n("OK", clickable = true))))
            val trace = mutableListOf<String>()
            val result = driver(ArrayDeque(listOf(unrelated)), trace = trace).execute(action(MacroStage.EnterMenu))
            assertTrue(result is MacroResult.Failure)
            assertTrue(trace.isEmpty())
        } finally {
            prefs.edit().clear().commit(); controller.destroy()
        }
    }
}
