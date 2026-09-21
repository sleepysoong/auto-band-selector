package com.sleepysoong.autobandselector.automation

import java.util.Collections

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private val KNOWN_TITLES = listOf(
    "Phone", "Warning", "Password", "SIM Selection", "Network Settings", "KT Hidden Menu", "Network Setting",
    "Network mode", "More options", "Band Selection", "ServiceMode"
)

data class NodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Each snapshot owns its child list; children are themselves immutable snapshots. */
class NodeSnapshot(
    val packageName: String,
    val className: String = "",
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val checkable: Boolean = false,
    val checked: Boolean? = null,
    val clickable: Boolean = false,
    children: List<NodeSnapshot> = emptyList(),
    val visible: Boolean = true,
    val scrollable: Boolean = false,
    val canScrollForward: Boolean? = null,
    val bounds: NodeBounds? = null
) {
    val children: List<NodeSnapshot> = immutableList(children)
}

data class WindowIdentity(val packageName: String, val windowClass: String)
data class WindowSnapshot(val identity: WindowIdentity, val root: NodeSnapshot)
data class ScreenFieldIds(
    val title: String,
    val digits: String,
    val password: String,
    val rat: String,
    val band: String,
    val sim: String
)

enum class ScreenKind {
    Dialer, Warning, HiddenPassword, SimSelection, HiddenMenu, NetworkSettings,
    NetworkMode, Overflow, BandSelection, RegisteredLte
}

class ScreenProfile(
    val window: WindowIdentity,
    allowedKinds: Set<ScreenKind>,
    val ids: ScreenFieldIds
) {
    val allowedKinds: Set<ScreenKind> = immutableSet(allowedKinds)
}

enum class CheckState { Checked, Unchecked, Unknown }

/** A structural address in one snapshot, never a coordinate or a cross-page identity. */
data class NodeRef(val path: String)
data class Control(val state: CheckState, val owner: NodeRef?)
data class BandRow(val band: Int, val control: Control)
data class SimOption(val slot: Int, val owner: NodeRef)
data class DialerControls(val digits: NodeRef, val finalEight: NodeRef)
enum class UnknownReason { UntrustedWindow, AmbiguousControl, Unsupported, InvalidScreen, ConflictingOverlap }

sealed class ScreenObservation {
    data class Unknown(val reason: UnknownReason) : ScreenObservation()
    object Dialer : ScreenObservation()
    object Warning : ScreenObservation()
    object HiddenPassword : ScreenObservation()
    class SimSelection(options: List<SimOption>) : ScreenObservation() {
        val options: List<SimOption> = immutableList(options)
    }
    object NetworkSettings : ScreenObservation()
    object HiddenMenu : ScreenObservation()
    data class NetworkMode(val automatic: Control) : ScreenObservation()
    object Overflow : ScreenObservation()
    data class RegisteredLte(val band: Int, val simSlot: Int) : ScreenObservation()
    class BandSelection(
        val selection: Control,
        rows: List<BandRow>,
        selectedUnknown: List<Control> = emptyList(),
        unreadableControls: List<Control> = emptyList()
    ) : ScreenObservation() {
        val rows: List<BandRow> = immutableList(rows)
        // Unknown labels and unlabeled controls must not be assigned a fabricated LTE band.
        val selectedUnknown: List<Control> = immutableList(selectedUnknown)
        val unreadableControls: List<Control> = immutableList(unreadableControls)

        fun selectedExclusions(target: Int): List<BandRow> =
            immutableList(rows.filter { it.band != target && it.control.state == CheckState.Checked })
    }
}

enum class TargetPresence { Visible, Offscreen, Missing, NotYetSeen }
enum class TraversalStatus { Complete, NextScroll, RepeatedPage, PageLimit, Unknown }
data class ScrollContainer(val path: String)
data class NextScroll(val container: ScrollContainer)

class TraversalResult(
    val target: TargetPresence,
    val status: TraversalStatus,
    pages: List<ScreenObservation.BandSelection>,
    rows: List<BandRow>,
    selectedExclusions: List<BandRow>,
    selectedUnknown: List<Control>,
    val nextScroll: NextScroll?
) {
    val pages: List<ScreenObservation.BandSelection> = immutableList(pages)
    val rows: List<BandRow> = immutableList(rows)
    val selectedExclusions: List<BandRow> = immutableList(selectedExclusions)
    val selectedUnknown: List<Control> = immutableList(selectedUnknown)
}

private class IndexedNode(
    val snapshot: NodeSnapshot,
    val ref: NodeRef,
    val parent: NodeRef?,
    children: List<NodeRef>,
    val visible: Boolean
) {
    val children: List<NodeRef> = immutableList(children)
    val label: String? get() = snapshot.text ?: snapshot.contentDescription

    fun control(): Control = Control(
        when (snapshot.checked) {
            true -> CheckState.Checked
            false -> CheckState.Unchecked
            null -> CheckState.Unknown
        },
        ref
    )
}

/** One preorder index per window. Reused snapshot objects still receive distinct paths. */
private class TreeIndex(snapshot: NodeSnapshot) {
    private val byRef: Map<NodeRef, IndexedNode>
    val nodes: List<IndexedNode>
    val root: IndexedNode get() = nodes.first()

    init {
        val entries = linkedMapOf<NodeRef, IndexedNode>()
        fun visit(node: NodeSnapshot, ref: NodeRef, parent: NodeRef?, parentVisible: Boolean,
                  clips: List<NodeBounds>) {
            val visible = parentVisible && node.visible && clips.all { clip ->
                clip.right > clip.left && clip.bottom > clip.top &&
                    (node.bounds == null || overlaps(node.bounds, clip))
            }
            val children = node.children.indices.map { NodeRef("${ref.path}/$it") }
            entries[ref] = IndexedNode(node, ref, parent, children, visible)
            val childClips = if (node.scrollable && node.bounds != null) clips + node.bounds else clips
            node.children.forEachIndexed { i, child -> visit(child, children[i], ref, visible, childClips) }
        }
        visit(snapshot, NodeRef("0"), null, true, emptyList())
        byRef = Collections.unmodifiableMap(entries)
        nodes = immutableList(entries.values)
    }

    fun parent(node: IndexedNode): IndexedNode? = node.parent?.let { byRef.getValue(it) }
    fun children(node: IndexedNode): List<IndexedNode> = node.children.map { byRef.getValue(it) }
    fun labels(label: String): List<IndexedNode> = nodes.filter { it.label == label }

    // Count fields before checking visibility: duplicate IDs never become a unique readback.
    fun field(id: String): IndexedNode? {
        val byId = if (id.isEmpty()) null else
            nodes.filter { it.snapshot.viewId == id }.singleOrNull()?.takeIf { it.visible }
        if (byId != null) return byId
        return nodes.filter { it.snapshot.className.contains("EditText") }
            .singleOrNull()?.takeIf { it.visible }
    }

    fun descendants(node: IndexedNode): Sequence<IndexedNode> = sequence {
        for (child in children(node)) {
            yield(child)
            yieldAll(descendants(child))
        }
    }

    fun hasClickOwner(label: IndexedNode): Boolean {
        var node: IndexedNode? = label
        while (node != null && node != root) {
            if (!node.visible) return false
            if (node.snapshot.clickable) return true
            node = parent(node)
        }
        return false
    }

    /** Prefer a checkable label, otherwise the nearest unambiguous parent/sibling row. */
    fun owner(label: IndexedNode, boundary: IndexedNode = root): Ownership {
        if (label.snapshot.checkable) return Ownership(label)
        var branch = label
        while (true) {
            val parent = parent(branch) ?: return Ownership()
            // A list/screen is not a row: never borrow a neighboring band's checkbox.
            if (parent == boundary || parent.snapshot.scrollable) return Ownership()
            val candidates = children(parent).filter {
                it.ref != branch.ref && it.snapshot.checkable
            } + listOfNotNull(parent.takeIf { it.snapshot.checkable })
            if (candidates.size > 1) return Ownership(ambiguous = true)
            if (candidates.size == 1) return Ownership(candidates.single())
            branch = parent
        }
    }

    private fun overlaps(a: NodeBounds, b: NodeBounds): Boolean =
        a.right > a.left && a.bottom > a.top &&
            a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}

private data class Ownership(val node: IndexedNode? = null, val ambiguous: Boolean = false) {
    fun control(): Control = node?.control() ?: Control(CheckState.Unknown, null)
}

private data class NodeSignature(
    val ref: NodeRef,
    val label: String?,
    val checkable: Boolean,
    val checked: Boolean?,
    val visible: Boolean
)

private data class PageSignature(val selection: Control, val contents: List<NodeSignature>)
private data class BandPage(
    val window: WindowIdentity,
    val observation: ScreenObservation.BandSelection,
    val container: ScrollContainer,
    val containerVisible: Boolean,
    val canScrollForward: Boolean?,
    val visibleBands: Set<Int>,
    val signature: PageSignature
)
private data class ParsedScreen(val observation: ScreenObservation, val bandPage: BandPage? = null)

/** Pure synthetic-tree parser. Profiles, not this class, establish trusted window identities. */
class SamsungScreenParser(profiles: List<ScreenProfile>) {
    private val profiles: List<ScreenProfile> = immutableList(profiles)

    fun parse(w: WindowSnapshot, expectedSimSlot: Int? = null): ScreenObservation =
        parseWindow(w, expectedSimSlot).observation

    private fun parseWindow(w: WindowSnapshot, expectedSimSlot: Int? = null): ParsedScreen {
        val profile = profiles.singleOrNull {
            it.window.packageName == w.identity.packageName &&
                (it.window.windowClass.isEmpty() || it.window.windowClass == w.identity.windowClass) &&
                it.allowedKinds.isNotEmpty()
        } ?: return ParsedScreen(unknown(UnknownReason.UntrustedWindow))
        val tree = TreeIndex(w.root)
        if (tree.nodes.any { it.snapshot.packageName != profile.window.packageName }) {
            return ParsedScreen(unknown(UnknownReason.UntrustedWindow))
        }
        val title = profile.ids.title.takeIf { it.isNotEmpty() }
            ?.let { id -> tree.field(id)?.label }
            ?: tree.labels("KT Hidden Menu").singleOrNull()?.label
            ?: KNOWN_TITLES.mapNotNull { known ->
                tree.labels(known).takeIf { it.size == 1 }
            }.singleOrNull()?.get(0)?.label
        val kind = when (title) {
            "Phone" -> ScreenKind.Dialer
            "Warning" -> ScreenKind.Warning
            "Password" -> ScreenKind.HiddenPassword
            "SIM Selection" -> ScreenKind.SimSelection
            "KT Hidden Menu" -> ScreenKind.HiddenMenu
            "Network Settings", "Network Setting" -> ScreenKind.NetworkSettings
            "Network mode" -> ScreenKind.NetworkMode
            "More options" -> ScreenKind.Overflow
            "Band Selection" -> ScreenKind.BandSelection
            "ServiceMode" -> ScreenKind.RegisteredLte
            else -> {
                // Localized dialers may expose no English title. Require a unique input and
                // every numeric key in the already trusted package before recognizing one.
                val keypad = ('0'..'9').all { hasButton(tree, it.toString()) }
                if (title == null && keypad && tree.field(profile.ids.digits) != null)
                    ScreenKind.Dialer
                else return ParsedScreen(unknown())
            }
        }
        if (kind !in profile.allowedKinds) return ParsedScreen(unknown())
        val observation = when (kind) {
            ScreenKind.Dialer -> parseDialer(tree, profile.ids)
            ScreenKind.Warning -> parseWarning(tree)
            ScreenKind.HiddenPassword -> parsePassword(tree, profile.ids)
            ScreenKind.SimSelection -> parseSimSelection(tree, profile.ids)
            ScreenKind.HiddenMenu -> if (hasButton(tree, "Network Setting")) ScreenObservation.HiddenMenu else unknown()
            ScreenKind.NetworkSettings -> parseNetworkSettings(tree)
            ScreenKind.NetworkMode -> parseNetworkMode(tree)
            ScreenKind.Overflow -> parseOverflow(tree)
            ScreenKind.RegisteredLte -> parseRegisteredLte(tree, profile.ids, expectedSimSlot)
            ScreenKind.BandSelection -> return parseBandSelection(tree, w.identity)
        }
        return ParsedScreen(observation)
    }

    private fun hasButton(tree: TreeIndex, label: String): Boolean =
        tree.labels(label).singleOrNull()?.let { tree.hasClickOwner(it) } == true

    private fun parseDialer(tree: TreeIndex, ids: ScreenFieldIds): ScreenObservation {
        val hasKey = tree.nodes.any {
            val label = it.label
            label != null && label.length == 1 && label[0] in '0'..'9' && tree.hasClickOwner(it)
        }
        return if (tree.field(ids.digits) != null && hasKey) ScreenObservation.Dialer else unknown()
    }

    private fun parseWarning(tree: TreeIndex): ScreenObservation =
        if (hasButton(tree, "OK")) ScreenObservation.Warning else unknown()

    private fun parsePassword(tree: TreeIndex, ids: ScreenFieldIds): ScreenObservation =
        if (tree.field(ids.password) != null && hasButton(tree, "OK")) ScreenObservation.HiddenPassword else unknown()

    private fun parseSimSelection(tree: TreeIndex, ids: ScreenFieldIds): ScreenObservation {
        val labels = tree.nodes.filter { it.snapshot.viewId != ids.title && it.label?.startsWith("SIM") == true }
        if (labels.isEmpty()) return unknown()
        val options = mutableListOf<SimOption>()
        for (label in labels) {
            val match = SIM_OPTION.matchEntire(label.label ?: "") ?: return unknown()
            if (!tree.hasClickOwner(label)) return unknown()
            val slot = match.groupValues[1].toInt()
            if (options.any { it.slot == slot }) return unknown()
            options += SimOption(slot, label.ref)
        }
        return ScreenObservation.SimSelection(options)
    }

    private fun parseNetworkSettings(tree: TreeIndex): ScreenObservation =
        if (hasButton(tree, "Network mode")) ScreenObservation.NetworkSettings else unknown()

    private fun parseNetworkMode(tree: TreeIndex): ScreenObservation {
        val label = tree.labels("Automatic").singleOrNull() ?: return unknown()
        val owner = tree.owner(label)
        return if (owner.ambiguous) unknown(UnknownReason.AmbiguousControl)
        else ScreenObservation.NetworkMode(owner.control())
    }

    private fun parseOverflow(tree: TreeIndex): ScreenObservation =
        if (hasButton(tree, "Band Selection")) ScreenObservation.Overflow else unknown()

    private fun parseRegisteredLte(tree: TreeIndex, ids: ScreenFieldIds, expectedSimSlot: Int?): ScreenObservation {
        val slot = expectedSimSlot ?: return unknown()
        if (slot !in 1..2 || tree.field(ids.rat)?.label != "LTE" || tree.field(ids.sim)?.label != "SIM $slot") {
            return unknown()
        }
        val band = canonicalBand(tree.field(ids.band)?.label ?: "") ?: return unknown()
        return ScreenObservation.RegisteredLte(band, slot)
    }

    private fun parseBandSelection(tree: TreeIndex, window: WindowIdentity): ParsedScreen {
        val container = tree.nodes.filter { it.snapshot.scrollable }.singleOrNull()
            ?: return ParsedScreen(unknown(UnknownReason.AmbiguousControl))
        val selectionLabels = tree.labels("SELECTION")
        if (selectionLabels.size > 1) return ParsedScreen(unknown(UnknownReason.AmbiguousControl))
        val selectionOwner = selectionLabels.singleOrNull()?.let { tree.owner(it) } ?: Ownership()
        if (selectionOwner.ambiguous) return ParsedScreen(unknown(UnknownReason.AmbiguousControl))
        val selection = selectionOwner.control()
        val contents = tree.descendants(container).toList()
        val rows = mutableListOf<BandRow>()
        val bands = mutableSetOf<Int>()
        val owners = mutableSetOf<NodeRef>()
        selection.owner?.let { owners += it }
        val visibleBands = mutableSetOf<Int>()
        val missingControls = mutableListOf<Control>()
        for (label in contents) {
            val band = canonicalBand(label.label ?: "") ?: continue
            val owner = tree.owner(label, container)
            val control = owner.control()
            if (!bands.add(band) || owner.ambiguous || (control.owner != null && !owners.add(control.owner))) {
                return ParsedScreen(unknown(UnknownReason.AmbiguousControl))
            }
            rows += BandRow(band, control)
            if (label.visible && owner.node?.visible == true) visibleBands += band
            if (control.owner == null) missingControls += control
        }
        val controls = contents.filter { it.snapshot.checkable && it.ref != selection.owner }.map { it.control() }
        val selectedUnknown = controls.filter { it.state == CheckState.Checked && it.owner !in owners }
        val unreadable = controls.filter { it.state == CheckState.Unknown } + missingControls
        val observation = ScreenObservation.BandSelection(selection, rows, selectedUnknown, unreadable)
        // Scroll flags and coordinates are not progress. Changed visible content is.
        val signature = PageSignature(selection, immutableList(contents.map {
            NodeSignature(it.ref, it.label, it.snapshot.checkable, it.snapshot.checked, it.visible)
        }))
        val page = BandPage(window, observation, ScrollContainer(container.ref.path), container.visible,
            container.snapshot.canScrollForward, immutableSet(visibleBands), signature)
        return ParsedScreen(observation, page)
    }

    /** Returns controls owned by this fresh, parser-verified dialer snapshot. */
    fun dialerControls(w: WindowSnapshot): DialerControls? {
        val parsed = parseWindow(w)
        if (parsed.observation !is ScreenObservation.Dialer) return null
        val tree = TreeIndex(w.root)
        val profile = profiles.singleOrNull {
            it.window.packageName == w.identity.packageName &&
                (it.window.windowClass.isEmpty() || it.window.windowClass == w.identity.windowClass)
        } ?: return null
        val digits = tree.field(profile.ids.digits) ?: return null
        val keys = tree.nodes.filter { it.label == "8" && tree.hasClickOwner(it) }
        val eight = keys.singleOrNull() ?: return null
        return DialerControls(digits.ref, eight.ref)
    }

    fun traverseBandPages(pages: List<WindowSnapshot>, target: Int): TraversalResult {
        val accumulator = BandPageAccumulator(target)
        var next: NextScroll? = null
        for (window in pages) {
            val page = parseWindow(window).bandPage ?: return accumulator.reject()
            val terminal = accumulator.add(page)
            if (terminal != null) return accumulator.result(terminal)
            next = NextScroll(page.container)
        }
        return if (next == null) accumulator.result(TraversalStatus.Unknown)
        else accumulator.result(TraversalStatus.NextScroll, next)
    }

    private fun unknown(reason: UnknownReason = UnknownReason.InvalidScreen) = ScreenObservation.Unknown(reason)

    companion object {
        private val LTE_BAND = Regex("LTE B([1-9][0-9]*)")
        private val SIM_OPTION = Regex("SIM ?([12])")

        fun canonicalBand(s: String): Int? = LTE_BAND.matchEntire(s)?.groupValues?.get(1)?.toIntOrNull()
    }
}

/** Accumulated radio identity is distinct from visibility on the most recent page. */
private class BandPageAccumulator(private val target: Int) {
    private val pages = mutableListOf<ScreenObservation.BandSelection>()
    private val rows = linkedMapOf<Int, BandRow>()
    private val signatures = mutableSetOf<PageSignature>()
    private var window: WindowIdentity? = null
    private var visibleBands: Set<Int> = emptySet()

    fun add(page: BandPage): TraversalStatus? {
        if (window != null && window != page.window) {
            visibleBands = emptySet()
            return TraversalStatus.Unknown
        }
        window = page.window
        visibleBands = page.visibleBands
        val conflict = page.observation.rows.any { row ->
            val previous = rows[row.band]
            previous != null && previous.control.state != row.control.state
        }
        if (conflict) {
            // Preserve both observations as evidence, but do not pick a winning state.
            pages += page.observation
            visibleBands = emptySet()
            return TraversalStatus.Unknown
        }
        if (!signatures.add(page.signature)) return TraversalStatus.RepeatedPage
        pages += page.observation
        page.observation.rows.forEach { rows[it.band] = it }
        if (!page.containerVisible || page.canScrollForward == null) return TraversalStatus.Unknown
        if (!page.canScrollForward) return TraversalStatus.Complete
        if (pages.size == MAX_PAGES) return TraversalStatus.PageLimit
        return null
    }

    fun reject(): TraversalResult {
        visibleBands = emptySet()
        return result(TraversalStatus.Unknown)
    }

    fun result(status: TraversalStatus, next: NextScroll? = null): TraversalResult {
        val presence = when {
            target in visibleBands -> TargetPresence.Visible
            target in rows -> TargetPresence.Offscreen
            status == TraversalStatus.Complete -> TargetPresence.Missing
            else -> TargetPresence.NotYetSeen
        }
        return TraversalResult(
            presence, status, pages, rows.values.toList(),
            rows.values.filter { it.band != target && it.control.state == CheckState.Checked },
            pages.flatMap { it.selectedUnknown }, next
        )
    }

    companion object {
        private const val MAX_PAGES = 20
    }
}
