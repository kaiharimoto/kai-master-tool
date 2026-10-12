package com.kaiharimoto.neue

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.runComposeUiTest
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuCheckbox
import com.kaiharimoto.neue.kit.MuSlider
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.MuTabs
import com.kaiharimoto.neue.theme.MuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a screen reader hears of the kit (Phase I, I.3): every control says what it is and what it does — a button its
 * words or, showing none, the label it gives the family cursor; a switch and a checkbox their state; a slider its name and
 * value; a tab whether it is the one chosen — and every one can be reached by the keyboard. The kit is what every page is
 * built of, so a page built of it is heard.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenReaderTest {

    private fun SemanticsNode.words(): String =
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
            .joinToString(" ")

    @Test
    fun everyControlOfTheKitSaysWhatItIsAndWhatItDoes() = runComposeUiTest {
        setContent {
            MuTheme(ink = false) {
                Column {
                    MuButton("Save", {})
                    MuButton("Run", {}, enabled = false, reason = "Open Shootout on a saved deck")
                    IconButton(Icons.Plus, {}, label = "Add one")
                    MicroLink("Show log", {})
                    MuSwitch(true, {}, label = "Auto save")
                    MuCheckbox(false, {}, label = "Groups")
                    MuSlider(0.5f, {}, name = "Zoom", valueText = "50%")
                    MuTabs("Art", listOf("Art", "List"), { it }, {})
                }
            }
        }
        val pressable = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = false).fetchSemanticsNodes()
        assertTrue(pressable.size >= 7, "the controls are there: ${pressable.size}")
        pressable.forEach { node ->
            assertTrue(node.words().isNotBlank(), "a control with nothing to say: ${node.config}")
            assertTrue(node.config.getOrNull(SemanticsProperties.Role) != null, "a control that does not say what it is: ${node.words()}")
            // A disabled control steps out of the keyboard's way, as it should; every other is reached.
            if (SemanticsProperties.Disabled !in node.config) assertTrue(SemanticsProperties.Focused in node.config, "the keyboard cannot reach ${node.words()}")
        }
        // Micro caps are set in capitals: words are matched as a reader hears them, case aside.
        fun one(words: String) = pressable.single { it.words().contains(words, ignoreCase = true) }
        assertEquals(Role.Button, one("Add one").config[SemanticsProperties.Role], "an icon button is named by its label")
        assertEquals("Open Shootout on a saved deck", one("Run").config.getOrNull(SemanticsProperties.StateDescription), "a disabled control says why")
        assertTrue(SemanticsProperties.Disabled in one("Run").config)
        assertEquals(ToggleableState.On, one("Auto save").config[SemanticsProperties.ToggleableState])
        assertEquals(Role.Switch, one("Auto save").config[SemanticsProperties.Role])
        assertEquals(ToggleableState.Off, one("Groups").config[SemanticsProperties.ToggleableState])
        assertEquals(Role.Checkbox, one("Groups").config[SemanticsProperties.Role])
        assertEquals(true, one("Art").config[SemanticsProperties.Selected])
        assertEquals(false, one("List").config[SemanticsProperties.Selected])
        assertEquals(Role.Tab, one("List").config[SemanticsProperties.Role])
        val slider = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).fetchSemanticsNodes().single()
        assertEquals(listOf("Zoom"), slider.config[SemanticsProperties.ContentDescription])
        assertEquals("50%", slider.config[SemanticsProperties.StateDescription])
    }
}
