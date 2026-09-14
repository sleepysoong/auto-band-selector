package com.sleepysoong.autobandselector.automation

enum class ScrollDirection { Forward, Backward }

/** Every invocation consumes one fresh immutable observation and performs at most one effect. */
class SamsungMacroDriver(
    private val parser: SamsungScreenParser,
    private val snapshot: () -> WindowSnapshot?,
    private val click: (NodeRef) -> Boolean,
    private val setText: (NodeRef, String) -> Boolean,
    private val isAuthorized: (MacroAction) -> Boolean,
    private val expectedSimSlot: Int,
    private val targetBand: Int? = null,
    private val scroll: (NodeRef, ScrollDirection) -> Boolean = { _, _ -> false },
    private val canScrollBackward: (NodeRef) -> Boolean? = { null },
    private val back: () -> Boolean = { false }
) {
    init { require(expectedSimSlot in 1..2) }

    private var dialPrefixRun: RunId? = null
    private var passwordRun: RunId? = null
    private var selectionClickRun: RunId? = null
    private var serviceCodeRun: RunId? = null
    private var automaticClickRun: RunId? = null
    private var backRun: RunId? = null
    private var bandRun: BandRun? = null

    fun isAuthorized(action: MacroAction): Boolean = isAuthorized.invoke(action)

    fun execute(action: MacroAction): MacroResult {
        if (!isAuthorized(action)) return rejected(action, "stale identity")
        val window = snapshot() ?: return rejected(action, "missing accessibility snapshot")
        val observation = parser.parse(window, expectedSimSlot)
        if (observation is ScreenObservation.Unknown) return rejected(action, observation.reason.name)
        return when (action.stage) {
            MacroStage.EnterMenu -> enterMenu(action, observation, window)
            MacroStage.ChooseSimIfShown -> chooseSim(action, observation, window)
            MacroStage.ReadBandPage -> verifyBandPage(action, observation)
            MacroStage.ConfigureCandidate, MacroStage.ApplyWinner -> configure(action, observation, window)
            MacroStage.DisableSelection -> setSelection(action, observation, false)
            MacroStage.NetworkModeAutomatic -> leaveForAutomatic(action, observation, window)
            MacroStage.VerifyAutomatic -> verifyAutomatic(action, observation)
            MacroStage.VerifyCandidate, MacroStage.VerifyWinner -> verifyFreshBandPage(action, observation)
            MacroStage.VerifyRegisteredBand -> verifyRegistered(action, observation, window)
            else -> navigation(action, observation, window)
        }
    }

    private fun chooseSim(action: MacroAction, observation: ScreenObservation, window: WindowSnapshot): MacroResult =
        when (observation) {
            is ScreenObservation.SimSelection -> {
                val option = observation.options.singleOrNull { it.slot == expectedSimSlot }
                    ?: return rejected(action, "resolved SIM slot is absent or ambiguous")
                val owner = findClickableLabel(window.root, "SIM ${option.slot}")
                    ?: findClickableLabel(window.root, "SIM${option.slot}")
                    ?: return rejected(action, "resolved SIM owner is unavailable")
                effectClick(action, owner, action.stage)
            }
            ScreenObservation.Warning -> effectClick(action,
                findClickableLabel(window.root, "OK") ?: return rejected(action, "warning control unavailable"),
                if (action.stage == MacroStage.EnterMenu) MacroStage.ChooseSimIfShown else action.stage)
            ScreenObservation.NetworkSettings -> effectClick(action,
                findClickableLabel(window.root, "Network mode") ?: return rejected(action, "network mode unavailable"), action.stage)
            is ScreenObservation.NetworkMode -> effectClick(action,
                findClickableLabel(window.root, "More options") ?: return rejected(action, "overflow control unavailable"), action.stage)
            ScreenObservation.Overflow -> effectClick(action,
                findClickableLabel(window.root, "Band Selection") ?: return rejected(action, "band selection unavailable"),
                MacroStage.ReadBandPage)
            is ScreenObservation.BandSelection ->
                if (action.mode == RunMode.Restore) MacroResult.Advance(action, MacroStage.DisableSelection)
                else navigation(action, observation, window)
            else -> rejected(action, "unexpected optional-SIM route screen")
        }

    private fun verifyBandPage(action: MacroAction, observation: ScreenObservation): MacroResult {
        val page = observation as? ScreenObservation.BandSelection ?: return rejected(action, "not band page")
        if (!page.isReadable()) return rejected(action, "selection state is unverifiable")
        if (action.mode == RunMode.Restore) return MacroResult.Advance(action, MacroStage.DisableSelection)
        val target = targetBand ?: return rejected(action, "missing target")
        if (page.rows.none { it.band == target } && page.rows.isEmpty()) return rejected(action, "target band unavailable")
        return MacroResult.Advance(action, next(action.stage))
    }

    private fun configure(action: MacroAction, observation: ScreenObservation, window: WindowSnapshot): MacroResult {
        val page = observation as? ScreenObservation.BandSelection ?: return rejected(action, "not band page")
        val target = targetBand ?: return rejected(action, "missing target")
        if (!page.isReadable()) return rejected(action, "band state is unverifiable")
        if (page.rows.any { it.control.owner == null }) return rejected(action, "band owner is unavailable")
        val container = findScrollable(window.root) ?: return rejected(action, "scroll container unavailable")
        val backward = canScrollBackward(container.ref)
        val run = bandRun?.takeIf { it.runId == action.runId && it.stage == action.stage }
            ?: BandRun(action.runId, action.stage).also { bandRun = it }

        run.pending?.let { pending ->
            val verified = when (pending) {
                is Pending.Band -> page.rows.singleOrNull { it.band == pending.band }?.control?.state == pending.state
                is Pending.Selection -> page.selection.state == pending.state
            }
            if (!verified) return rejected(action, "fresh mutation readback mismatched")
            run.pending = null
        }

        if (++run.observations > MAX_PAGES * 8) return rejected(action, "band traversal observation limit")
        return when (run.phase) {
            BandPhase.Mutate -> mutate(action, page, container, backward, run, target)
            BandPhase.Rewind -> rewind(action, container, backward, run)
            BandPhase.Verify -> verifyConfiguration(action, page, container, run, target)
        }
    }

    private fun mutate(action: MacroAction, page: ScreenObservation.BandSelection, container: ScrollNode,
                       backward: Boolean?, run: BandRun, target: Int): MacroResult {
        val signature = page.pageSignature()
        if (run.awaitingForwardScroll && signature in run.pageSignatures)
            return rejected(action, "repeated band page")
        run.awaitingForwardScroll = false
        run.pageSignatures += signature
        if (run.pageSignatures.size > MAX_PAGES) return rejected(action, "band page limit")

        page.selectedExclusions(target).firstOrNull()?.let { row ->
            if (++run.mutations > MAX_MUTATIONS) return rejected(action, "band mutation limit")
            run.pending = Pending.Band(row.band, CheckState.Unchecked)
            return effectClick(action, checkNotNull(row.control.owner), action.stage)
        }
        page.rows.singleOrNull { it.band == target }?.let { row ->
            run.targetSeen = true
            if (row.control.state == CheckState.Unchecked) {
                if (++run.mutations > MAX_MUTATIONS) return rejected(action, "band mutation limit")
                run.pending = Pending.Band(target, CheckState.Checked)
                return effectClick(action, checkNotNull(row.control.owner), action.stage)
            }
        }
        if (container.forward == true) {
            run.awaitingForwardScroll = true
            return effectScroll(action, container.ref, ScrollDirection.Forward)
        }
        if (container.forward == null) return rejected(action, "unreadable scroll boundary")
        if (!run.targetSeen) return rejected(action, "target band missing after complete traversal")
        if (page.selection.state == CheckState.Unchecked) {
            if (++run.mutations > MAX_MUTATIONS) return rejected(action, "band mutation limit")
            run.pending = Pending.Selection(CheckState.Checked)
            return effectClick(action, checkNotNull(page.selection.owner), action.stage)
        }
        run.phase = BandPhase.Rewind
        run.pageSignatures.clear()
        return rewind(action, container, backward, run)
    }

    private fun rewind(action: MacroAction, container: ScrollNode, backward: Boolean?, run: BandRun): MacroResult {
        if (backward == null) return rejected(action, "unreadable reverse scroll boundary")
        if (backward) return effectScroll(action, container.ref, ScrollDirection.Backward)
        run.phase = BandPhase.Verify
        run.targetSeen = false
        run.pageSignatures.clear()
        return MacroResult.Advance(action, action.stage)
    }

    private fun verifyConfiguration(action: MacroAction, page: ScreenObservation.BandSelection,
                                    container: ScrollNode, run: BandRun, target: Int): MacroResult {
        val signature = page.verificationSignature()
        if (!run.pageSignatures.add(signature)) return rejected(action, "repeated verification page")
        if (run.pageSignatures.size > MAX_PAGES) return rejected(action, "verification page limit")
        if (page.selection.state != CheckState.Checked) return rejected(action, "SELECTION is not on")
        if (page.selectedExclusions(target).isNotEmpty()) return rejected(action, "non-target remains selected")
        page.rows.singleOrNull { it.band == target }?.let {
            run.targetSeen = true
            if (it.control.state != CheckState.Checked) return rejected(action, "target is not selected")
        }
        if (container.forward == null) return rejected(action, "unreadable verification boundary")
        if (container.forward) return effectScroll(action, container.ref, ScrollDirection.Forward)
        if (!run.targetSeen) return rejected(action, "target absent from fresh verification")
        bandRun = null
        return MacroResult.Advance(action, next(action.stage))
    }

    private fun setSelection(action: MacroAction, observation: ScreenObservation, enabled: Boolean): MacroResult {
        val page = observation as? ScreenObservation.BandSelection ?: return rejected(action, "not band page")
        if (page.selection.state == CheckState.Unknown || page.selection.owner == null)
            return rejected(action, "selection state is unverifiable")
        if ((page.selection.state == CheckState.Checked) == enabled) {
            selectionClickRun = null
            return MacroResult.Advance(action, next(action.stage))
        }
        if (selectionClickRun == action.runId) return rejected(action, "fresh SELECTION readback mismatched")
        val result = effectClick(action, page.selection.owner, action.stage)
        if (result is MacroResult.Advance) selectionClickRun = action.runId
        return result
    }

    private fun verifyFreshBandPage(action: MacroAction, observation: ScreenObservation): MacroResult {
        val page = observation as? ScreenObservation.BandSelection ?: return rejected(action, "fresh band readback unavailable")
        if (!page.isReadable()) return rejected(action, "fresh band readback is unverifiable")
        val target = targetBand ?: return rejected(action, "missing target")
        if (page.selectedExclusions(target).isNotEmpty()) return rejected(action, "fresh readback found non-target selection")
        val targetRow = page.rows.singleOrNull { it.band == target }
            ?: return rejected(action, "fresh readback lacks target band")
        if (targetRow.control.state != CheckState.Checked) return rejected(action, "fresh readback target not selected")
        if (page.selection.state != CheckState.Checked) return rejected(action, "fresh readback SELECTION not on")
        return MacroResult.Complete(action)
    }

    private fun leaveForAutomatic(action: MacroAction, observation: ScreenObservation, window: WindowSnapshot): MacroResult {
        if (action.mode != RunMode.Restore) return setAutomatic(action, observation, window)
        if (observation is ScreenObservation.BandSelection) {
            if (backRun == action.runId) return rejected(action, "back did not leave the band page")
            if (!isAuthorized(action)) return failed(action, "back rejected")
            if (!back()) return failed(action, "back rejected")
            backRun = action.runId
            return MacroResult.Advance(action, action.stage)
        }
        return setAutomatic(action, observation, window)
    }

    private fun setAutomatic(action: MacroAction, observation: ScreenObservation, window: WindowSnapshot): MacroResult {
        val mode = observation as? ScreenObservation.NetworkMode ?: return navigation(action, observation, window)
        if (mode.automatic.state == CheckState.Unknown || mode.automatic.owner == null)
            return rejected(action, "automatic state is unverifiable")
        if (mode.automatic.state == CheckState.Checked) return MacroResult.Advance(action, next(action.stage))
        if (automaticClickRun == action.runId) return rejected(action, "fresh Automatic readback mismatched")
        val result = effectClick(action, mode.automatic.owner, action.stage)
        if (result is MacroResult.Advance) automaticClickRun = action.runId
        return result
    }

    private fun verifyAutomatic(action: MacroAction, observation: ScreenObservation): MacroResult {
        val mode = observation as? ScreenObservation.NetworkMode ?: return rejected(action, "automatic readback unavailable")
        return if (mode.automatic.state == CheckState.Checked)
            MacroResult.Complete(action, RecoveryStatus.AutomaticVerified)
        else rejected(action, "automatic mode not verified")
    }

    private fun verifyRegistered(action: MacroAction, observation: ScreenObservation, window: WindowSnapshot): MacroResult {
        if (observation is ScreenObservation.Dialer) {
            if (serviceCodeRun == action.runId) return MacroResult.Advance(action, action.stage)
            val controls = parser.dialerControls(window) ?: return rejected(action, "service dialer controls unavailable")
            if (!authorizedSetText(action, controls.digits, SERVICE_CODE)) return failed(action, "service code entry rejected")
            serviceCodeRun = action.runId
            return MacroResult.Advance(action, action.stage)
        }
        val registered = observation as? ScreenObservation.RegisteredLte
            ?: return rejected(action, "registered LTE readback unavailable")
        val target = targetBand ?: return rejected(action, "missing target")
        return if (registered.band == target && registered.simSlot == expectedSimSlot)
            MacroResult.Advance(action, next(action.stage))
        else rejected(action, "registered LTE readback mismatched")
    }

    private fun navigation(action: MacroAction, observation: ScreenObservation, window: WindowSnapshot): MacroResult {
        return when (observation) {
            ScreenObservation.Warning -> effectClick(action,
                findClickableLabel(window.root, "OK") ?: return rejected(action, "warning control unavailable"),
                if (action.stage == MacroStage.EnterMenu) MacroStage.ChooseSimIfShown else action.stage)
            ScreenObservation.HiddenPassword -> password(action, window)
            is ScreenObservation.SimSelection ->
                if (action.stage == MacroStage.EnterMenu) MacroResult.Advance(action, MacroStage.ChooseSimIfShown)
                else rejected(action, "unexpected SIM screen")
            ScreenObservation.NetworkSettings ->
                if (action.stage == MacroStage.EnterMenu) MacroResult.Advance(action, MacroStage.ChooseSimIfShown)
                else effectClick(action,
                    findClickableLabel(window.root, "Network mode") ?: return rejected(action, "network mode unavailable"), action.stage)
            ScreenObservation.Overflow -> effectClick(action,
                findClickableLabel(window.root, "Band Selection") ?: return rejected(action, "band selection unavailable"), MacroStage.ReadBandPage)
            is ScreenObservation.NetworkMode -> effectClick(action,
                findClickableLabel(window.root, "More options") ?: return rejected(action, "overflow control unavailable"), action.stage)
            else -> rejected(action, "unexpected screen")
        }
    }

    private fun password(action: MacroAction, window: WindowSnapshot): MacroResult {
        val field = findField(window.root, "password") ?: return rejected(action, "password field unavailable")
        val value = nodeAt(window.root, field)?.text
        if (passwordRun == action.runId && value == PASSWORD) {
            val ok = findClickableLabel(window.root, "OK") ?: return rejected(action, "password confirmation unavailable")
            return effectClick(action, ok, MacroStage.ChooseSimIfShown)
        }
        if (!authorizedSetText(action, field, PASSWORD)) return failed(action, "password entry rejected")
        passwordRun = action.runId
        return MacroResult.Advance(action, action.stage)
    }

    private fun enterMenu(action: MacroAction, observation: ScreenObservation, window: WindowSnapshot): MacroResult {
        if (observation !is ScreenObservation.Dialer) return navigation(action, observation, window)
        val controls = parser.dialerControls(window) ?: return rejected(action, "dialer controls unavailable")
        val normalized = nodeAt(window.root, controls.digits)?.text?.filter(Char::isDigit).orEmpty()
        val prefix = SamsungPhoneEntry.DIAL_NUMBER.dropLast(1)
        return when {
            normalized == prefix && dialPrefixRun == action.runId -> {
                val owner = findClickableLabel(window.root, "8")
                    ?: return rejected(action, "final digit owner unavailable")
                if (!authorizedClick(action, owner)) failed(action, "final digit click rejected")
                else MacroResult.Advance(action, action.stage)
            }
            normalized == SamsungPhoneEntry.DIAL_NUMBER -> MacroResult.Advance(action, action.stage)
            else -> {
                if (!authorizedSetText(action, controls.digits, prefix)) failed(action, "dialer entry rejected")
                else {
                    dialPrefixRun = action.runId
                    MacroResult.Advance(action, action.stage)
                }
            }
        }
    }

    private fun ScreenObservation.BandSelection.isReadable(): Boolean =
        selection.state != CheckState.Unknown && selection.owner != null &&
            selectedUnknown.isEmpty() && unreadableControls.isEmpty() &&
            rows.all { it.control.state != CheckState.Unknown }

    private fun ScreenObservation.BandSelection.pageSignature(): String = buildString {
        rows.sortedBy { it.band }.forEach {
            append('|').append(it.band).append('@').append(it.control.owner?.path)
        }
    }

    private fun ScreenObservation.BandSelection.verificationSignature(): String = buildString {
        append(selection.state)
        rows.sortedBy { it.band }.forEach {
            append('|').append(it.band).append(':').append(it.control.state)
                .append('@').append(it.control.owner?.path)
        }
    }

    private fun findScrollable(root: NodeSnapshot): ScrollNode? {
        fun walk(node: NodeSnapshot, path: String): List<ScrollNode> =
            (if (node.scrollable) listOf(ScrollNode(NodeRef(path), node.canScrollForward)) else emptyList()) +
                node.children.flatMapIndexed { index, child -> walk(child, "$path/$index") }
        return walk(root, "0").singleOrNull()
    }

    private fun nodeAt(root: NodeSnapshot, ref: NodeRef): NodeSnapshot? {
        var node = root
        for (part in ref.path.split('/').drop(1)) node = node.children.getOrNull(part.toInt()) ?: return null
        return node
    }

    private fun findField(root: NodeSnapshot, id: String): NodeRef? = findNode(root, { it.viewId == id })

    private fun findClickableLabel(root: NodeSnapshot, label: String): NodeRef? {
        val matches = mutableListOf<NodeRef>()
        fun walk(node: NodeSnapshot, path: String, clickableAncestor: NodeRef?) {
            val current = NodeRef(path)
            val owner = if (node.clickable) current else clickableAncestor
            if ((node.text ?: node.contentDescription) == label && owner != null) matches += owner
            node.children.forEachIndexed { index, child -> walk(child, "$path/$index", owner) }
        }
        walk(root, "0", null)
        return matches.distinct().singleOrNull()
    }

    private fun findNode(node: NodeSnapshot, matches: (NodeSnapshot) -> Boolean, path: String = "0"): NodeRef? {
        if (matches(node)) return NodeRef(path)
        return node.children.mapIndexedNotNull { i, child -> findNode(child, matches, "$path/$i") }.firstOrNull()
    }

    private fun authorizedClick(action: MacroAction, ref: NodeRef): Boolean = isAuthorized(action) && click(ref)
    private fun authorizedSetText(action: MacroAction, ref: NodeRef, value: String): Boolean = isAuthorized(action) && setText(ref, value)
    private fun authorizedScroll(action: MacroAction, ref: NodeRef, direction: ScrollDirection): Boolean =
        isAuthorized(action) && scroll(ref, direction)

    private fun effectClick(action: MacroAction, ref: NodeRef, nextStage: MacroStage): MacroResult =
        if (authorizedClick(action, ref)) MacroResult.Advance(action, nextStage) else failed(action, "click rejected")

    private fun effectScroll(action: MacroAction, ref: NodeRef, direction: ScrollDirection): MacroResult =
        if (authorizedScroll(action, ref, direction)) MacroResult.Advance(action, action.stage) else failed(action, "scroll rejected")

    private fun next(stage: MacroStage) = when (stage) {
        MacroStage.ReadBandPage -> MacroStage.ConfigureCandidate
        MacroStage.ConfigureCandidate -> MacroStage.VerifyCandidate
        MacroStage.ApplyWinner -> MacroStage.VerifyWinner
        MacroStage.DisableSelection -> MacroStage.NetworkModeAutomatic
        MacroStage.NetworkModeAutomatic -> MacroStage.VerifyAutomatic
        MacroStage.VerifyCandidate -> MacroStage.AwaitCellular
        MacroStage.VerifyWinner -> MacroStage.VerifyRegisteredBand
        MacroStage.VerifyRegisteredBand -> MacroStage.MeasureCandidate
        else -> MacroStage.ReadBandPage
    }

    private fun rejected(action: MacroAction, message: String) =
        MacroResult.Failure(action, FailureReason.Rejected("unsupported: $message"))
    private fun failed(action: MacroAction, message: String) =
        MacroResult.Failure(action, FailureReason.EffectError("accessibility", message))

    private enum class BandPhase { Mutate, Rewind, Verify }
    private sealed interface Pending {
        data class Band(val band: Int, val state: CheckState) : Pending
        data class Selection(val state: CheckState) : Pending
    }
    private data class ScrollNode(val ref: NodeRef, val forward: Boolean?)
    private class BandRun(val runId: RunId, val stage: MacroStage) {
        var phase = BandPhase.Mutate
        var pending: Pending? = null
        var targetSeen = false
        var observations = 0
        var awaitingForwardScroll = false
        var mutations = 0
        val pageSignatures = mutableSetOf<String>()
    }

    companion object {
        private const val PASSWORD = "774632"
        private const val SERVICE_CODE = "*123456#"
        private const val MAX_PAGES = 20
        private const val MAX_MUTATIONS = 256
    }
}
