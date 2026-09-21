package com.sleepysoong.autobandselector

import com.sleepysoong.autobandselector.automation.*
import org.junit.Assert.*
import org.junit.Test

/** Entirely synthetic trees: no captured Samsung/Fold6 UI compatibility is asserted. */
class SamsungScreenParserTest {
    private val window = WindowIdentity("synthetic.samsung", "SyntheticHiddenWindow")
    private val ids = ScreenFieldIds("title", "digits", "password", "rat", "band", "sim")
    private val parser = SamsungScreenParser(listOf(ScreenProfile(window, ScreenKind.entries.toSet(), ids)))
    private fun node(text: String? = null, id: String? = null, checkable: Boolean = false,
                     checked: Boolean? = null, clickable: Boolean = false,
                     children: List<NodeSnapshot> = emptyList(), visible: Boolean = true,
                     scrollable: Boolean = false, forward: Boolean? = null,
                     bounds: NodeBounds? = null, description: String? = null) = NodeSnapshot(
        packageName = window.packageName, className = "synthetic.View", viewId = id,
        text = text, contentDescription = description, checkable = checkable, checked = checked,
        clickable = clickable, children = children, visible = visible, scrollable = scrollable,
        canScrollForward = forward, bounds = bounds)
    private fun screen(title: String, vararg children: NodeSnapshot) = WindowSnapshot(window,
        node(children = listOf(node(title, "title")) + children))
    private fun row(label: String, checked: Boolean? = false, visible: Boolean = true) =
        node(checkable = true, checked = checked, clickable = true, visible = visible,
            children = listOf(node(label)))
    private fun bands(vararg rows: NodeSnapshot, selection: NodeSnapshot = row("SELECTION", true),
                      forward: Boolean? = false) = screen("Band Selection", selection,
        node(id = "list", scrollable = true, forward = forward, children = rows.toList()))
    private fun page(tree: WindowSnapshot) = parser.parse(tree) as ScreenObservation.BandSelection

    @Test fun productionDialerWithoutEnglishTitleRequiresCompleteKeypadAndUniqueInput() {
        val pkg = SamsungPhoneEntry.SAMSUNG_PHONE_PACKAGE
        val input = NodeSnapshot(pkg, "android.widget.EditText", text = "31971235")
        val keys = ('0'..'9').map { NodeSnapshot(pkg, text = it.toString(), clickable = true) }
        fun window(children: List<NodeSnapshot>, packageName: String = pkg) = WindowSnapshot(
            WindowIdentity(packageName, "android.widget.FrameLayout"), NodeSnapshot(pkg, children = children))
        val production = SamsungProfiles.production()
        val localized = window(listOf(NodeSnapshot(pkg, text = "전화"), input) + keys)
        assertTrue(production.parse(localized) is ScreenObservation.Dialer)
        assertNotNull(production.dialerControls(localized))
        assertTrue(production.parse(window(listOf(input) + keys.dropLast(1))) is ScreenObservation.Unknown)
        assertTrue(production.parse(window(listOf(input, input) + keys)) is ScreenObservation.Unknown)
        assertTrue(production.parse(window(listOf(input) + keys, "other.app")) is ScreenObservation.Unknown)
    }

    @Test fun ktMenuNeedsUniqueClickableNetworkSettingInTrustedPackage() {
        val pkg = SamsungProfiles.HIDDEN_MENU_PACKAGE
        fun menu(targets: List<NodeSnapshot>, packageName: String = pkg) = WindowSnapshot(
            WindowIdentity(packageName, "View"), NodeSnapshot(packageName, children =
                listOf(NodeSnapshot(packageName, text = "KT Hidden Menu")) + targets))
        val target = NodeSnapshot(pkg, text = "Network Setting", clickable = true)
        val production = SamsungProfiles.production()
        assertTrue(production.parse(menu(listOf(target))) is ScreenObservation.HiddenMenu)
        assertTrue(production.parse(menu(listOf(target, target))) is ScreenObservation.Unknown)
        assertTrue(production.parse(menu(emptyList())) is ScreenObservation.Unknown)
        assertTrue(production.parse(menu(emptyList(), "other.app")) is ScreenObservation.Unknown)
    }

    @Test fun canonicalBandIsExactNotAPrefix() {
        listOf(1, 3, 8, 10, 18, 19).forEach { assertEquals(it, SamsungScreenParser.canonicalBand("LTE B$it")) }
        listOf("LTE B01", "LTE B0", "LTE B1 extra", " LTE B1", "LTE B1\n", "NR B1", "lte b1", "LTE B999999999999999").forEach {
            assertNull(it, SamsungScreenParser.canonicalBand(it))
        }
    }

    @Test fun variedRowOrderAndB1CollisionsPreserveAllCheckedStates() {
        val orders = listOf(listOf(1, 10, 18, 19, 3, 8), listOf(19, 8, 18, 3, 10, 1))
        orders.forEach { order ->
            val result = page(bands(*order.map { row("LTE B$it", it == 18) }.toTypedArray()))
            assertEquals(order, result.rows.map { it.band })
            assertEquals(CheckState.Unchecked, result.rows.single { it.band == 1 }.control.state)
            assertEquals(CheckState.Checked, result.rows.single { it.band == 18 }.control.state)
            assertEquals(listOf(18), result.selectedExclusions(1).map { it.band })
        }
    }

    @Test fun unknownPackageWindowAndMixedPackagePasswordFailClosed() {
        val password = screen("Password", node(id = "password"), node("OK", clickable = true))
        assertTrue(parser.parse(password) is ScreenObservation.HiddenPassword)
        listOf(WindowIdentity("other.app", window.windowClass), WindowIdentity(window.packageName, "OtherWindow")).forEach {
            assertEquals(UnknownReason.UntrustedWindow, (parser.parse(WindowSnapshot(it, password.root)) as ScreenObservation.Unknown).reason)
        }
        val mixed = WindowSnapshot(window, NodeSnapshot(packageName = "other.app", children = password.root.children))
        assertTrue(parser.parse(mixed) is ScreenObservation.Unknown)
        assertTrue(parser.parse(screen("Password")) is ScreenObservation.Unknown)
    }

    @Test fun exactScreensAndRequiredControlsAreRecognized() {
        assertTrue(parser.parse(screen("Phone", node("31971235", "digits"), node("8", clickable = true))) is ScreenObservation.Dialer)
        assertTrue(parser.parse(screen("Warning", node("OK", clickable = true))) is ScreenObservation.Warning)
        val sim = parser.parse(screen("SIM Selection", node("SIM 2", clickable = true), node("SIM1", clickable = true))) as ScreenObservation.SimSelection
        assertEquals(listOf(2, 1), sim.options.map { it.slot })
        assertTrue(parser.parse(screen("Network Settings", node("Network mode", clickable = true))) is ScreenObservation.NetworkSettings)
        val mode = parser.parse(screen("Network mode", row("Automatic", true))) as ScreenObservation.NetworkMode
        assertEquals(CheckState.Checked, mode.automatic.state)
        assertTrue(parser.parse(screen("More options", node("Band Selection", clickable = true))) is ScreenObservation.Overflow)
        assertTrue(parser.parse(screen("Password help", node(id = "password"), node("OK", clickable = true))) is ScreenObservation.Unknown)
        assertTrue(parser.parse(screen("SIM Selection", node("SIM 3", clickable = true))) is ScreenObservation.Unknown)
    }

    @Test fun profileLimitsWhichScreenCanOccurInAWindow() {
        val onlyBands = SamsungScreenParser(listOf(ScreenProfile(window, setOf(ScreenKind.BandSelection), ids)))
        assertTrue(onlyBands.parse(screen("Password", node(id = "password"), node("OK", clickable = true))) is ScreenObservation.Unknown)
    }

    @Test fun selectionTextWithoutCheckableOwnerIsUnknownNotOff() {
        val result = page(bands(row("LTE B1"), selection = node("SELECTION")))
        assertEquals(CheckState.Unknown, result.selection.state)
        assertNull(result.selection.owner)
        assertEquals(CheckState.Unknown, page(bands(row("LTE B1"), selection = row("SELECTION", null))).selection.state)
        assertEquals(CheckState.Unchecked, page(bands(row("LTE B1"), selection = row("SELECTION", false))).selection.state)
    }

    @Test fun parentAndSiblingOwnershipAreDeterministic() {
        val sibling = node(children = listOf(node("LTE B1"), node(checkable = true, checked = true, clickable = true)))
        val result = page(bands(sibling, row("LTE B18", false)))
        assertEquals(CheckState.Checked, result.rows.first().control.state)
        assertEquals("0/2/0/1", result.rows.first().control.owner!!.path)
        assertEquals(CheckState.Unchecked, result.rows.last().control.state)
    }

    @Test fun ambiguousOwnersSharedOwnerAndDuplicateBandAreUnknown() {
        val ambiguous = node(children = listOf(node("LTE B1"), node(checkable = true, checked = true), node(checkable = true, checked = false)))
        val shared = node(checkable = true, checked = true, children = listOf(node("LTE B1"), node("LTE B18")))
        listOf(bands(ambiguous), bands(shared), bands(row("LTE B1"), row("LTE B1"))).forEach {
            assertEquals(UnknownReason.AmbiguousControl, (parser.parse(it) as ScreenObservation.Unknown).reason)
        }
    }

    @Test fun unknownSelectedAndUnreadableControlsAreNotDiscarded() {
        val result = page(bands(row("LTE B1", true), row("LTE B18", true), row("NR N78", true),
            node(checkable = true, checked = true), row("LTE B3", null), node(checkable = true)))
        assertEquals(listOf(18), result.selectedExclusions(1).map { it.band })
        assertEquals(2, result.selectedUnknown.size)
        assertEquals(2, result.unreadableControls.size)
        assertEquals(CheckState.Unknown, result.rows.single { it.band == 3 }.control.state)
    }

    @Test fun offscreenAndMissingTargetsNeverBecomeVisibleOrVerified() {
        val result = parser.traverseBandPages(listOf(bands(row("LTE B18", true), row("LTE B1", true, visible = false))), 1)
        assertEquals(TargetPresence.Offscreen, result.target)
        assertEquals(TraversalStatus.Complete, result.status)
        val missing = parser.traverseBandPages(listOf(bands(row("LTE B18"))), 1)
        assertEquals(TargetPresence.Missing, missing.target)
        val pending = parser.traverseBandPages(listOf(bands(row("LTE B18"), forward = true)), 1)
        assertEquals(TargetPresence.NotYetSeen, pending.target)
        assertEquals(TraversalStatus.NextScroll, pending.status)
        assertEquals("0/2", pending.nextScroll!!.container.path)
    }

    @Test fun boundsOutsideContainerAreOffscreenWithoutCoordinateActions() {
        val tree = screen("Band Selection", row("SELECTION", true), node(scrollable = true, forward = false,
            bounds = NodeBounds(0, 0, 100, 100), children = listOf(node("LTE B1", checkable = true, checked = true,
                bounds = NodeBounds(0, 110, 100, 150)))))
        assertEquals(TargetPresence.Offscreen, parser.traverseBandPages(listOf(tree), 1).target)
    }

    @Test fun completeTraversalIncludesLaterSelectedExclusionAndOffscreenTarget() {
        val result = parser.traverseBandPages(listOf(bands(row("LTE B1", true), forward = true),
            bands(row("LTE B18", true), row("NR N78", true), forward = false)), 1)
        assertEquals(TraversalStatus.Complete, result.status)
        assertEquals(TargetPresence.Offscreen, result.target)
        assertEquals(listOf(1, 18), result.rows.map { it.band })
        assertEquals(listOf(18), result.selectedExclusions.map { it.band })
        assertEquals(1, result.selectedUnknown.size)
    }

    @Test fun repeatedPageAndTwentyPageLimitTerminateWithoutSuccess() {
        val first = bands(row("LTE B18"), forward = true)
        val repeated = parser.traverseBandPages(listOf(first, first), 1)
        assertEquals(TraversalStatus.RepeatedPage, repeated.status)
        assertNull(repeated.nextScroll)
        val pages = (1..20).map { bands(row("LTE B$it"), forward = true) }
        assertEquals(TraversalStatus.NextScroll, parser.traverseBandPages(pages.take(19), 30).status)
        val limit = parser.traverseBandPages(pages, 30)
        assertEquals(TraversalStatus.PageLimit, limit.status)
        assertEquals(20, limit.pages.size)
        assertNull(limit.nextScroll)
        assertEquals(TraversalStatus.PageLimit, parser.traverseBandPages(pages + bands(row("LTE B30")), 30).status)
        assertEquals(TraversalStatus.Complete, parser.traverseBandPages(pages.take(19) + bands(row("LTE B20")), 30).status)
    }

    @Test fun unknownScrollDirectionMultipleContainersAndConflictingOverlapFailClosed() {
        assertEquals(TraversalStatus.Unknown, parser.traverseBandPages(listOf(bands(row("LTE B1"), forward = null)), 1).status)
        val two = screen("Band Selection", row("SELECTION", true), node(scrollable = true, forward = true), node(scrollable = true, forward = true))
        assertTrue(parser.parse(two) is ScreenObservation.Unknown)
        assertEquals(TraversalStatus.Unknown, parser.traverseBandPages(listOf(bands(row("LTE B1", true), forward = true), bands(row("LTE B1", false))), 1).status)
    }

    private fun service(rat: String = "LTE", band: String = "LTE B1", sim: String = "SIM 2", extra: List<NodeSnapshot> = emptyList()) =
        screen("ServiceMode", node(rat, "rat"), node(band, "band"), node(sim, "sim"), *extra.toTypedArray())

    @Test fun registeredReadbackRequiresExactLteBandAndExpectedSimFields() {
        val result = parser.parse(service(), expectedSimSlot = 2) as ScreenObservation.RegisteredLte
        assertEquals(1, result.band)
        assertEquals(2, result.simSlot)
        listOf(service(rat = "NR"), service(rat = "LTE + NR"), service(band = "B1"), service(sim = "SIM 1"),
            service(extra = listOf(node("NR", "rat"))), service(extra = listOf(node("LTE B18", "band"))),
            screen("ServiceMode", node("LTE", "rat"), node("LTE B1", "band"))).forEach {
            assertTrue(parser.parse(it, expectedSimSlot = 2) is ScreenObservation.Unknown)
        }
        assertTrue(parser.parse(service()) is ScreenObservation.Unknown)
    }

    @Test fun inputAndObservationCollectionsCannotBeMutated() {
        val children = mutableListOf(row("LTE B1", true))
        val root = node(children = children)
        children.clear()
        assertEquals(1, root.children.size)
        assertThrows(UnsupportedOperationException::class.java) { (root.children as MutableList).clear() }
        val result = page(bands(row("LTE B1")))
        assertThrows(UnsupportedOperationException::class.java) { (result.rows as MutableList).clear() }
        val kinds = mutableSetOf(ScreenKind.BandSelection)
        val profiles = mutableListOf(ScreenProfile(window, kinds, ids))
        val isolated = SamsungScreenParser(profiles)
        kinds.clear(); profiles.clear()
        assertTrue(isolated.parse(bands(row("LTE B1"))) is ScreenObservation.BandSelection)
    }

    @Test fun syntheticParserResultDumpDistinguishesB1FromB18() {
        val result = page(bands(row("LTE B1", false), row("LTE B18", true)))
        val json = "{\"fixture\":\"synthetic\",\"target\":1,\"rows\":[" + result.rows.joinToString(",") {
            "{\"band\":${it.band},\"checked\":${it.control.state == CheckState.Checked}}"
        } + "],\"selectedExclusions\":[" + result.selectedExclusions(1).joinToString(",") { it.band.toString() } + "]}"
        assertEquals(listOf(18), result.selectedExclusions(1).map { it.band })
        println(json)
    }
}
