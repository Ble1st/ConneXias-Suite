package de.ble1st.files.ui.browser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ble1st.files.R
import de.ble1st.files.data.fileops.ConflictPolicy
import de.ble1st.files.data.fs.FileEntry
import de.ble1st.files.util.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Die beiden Dialoge, an denen im Fehlerfall Daten verloren gehen: die Papierkorb-Bestätigung und
 * die Konflikt-Auflösung beim Einfügen. Beide entscheiden über fremde Dateien, und beide waren
 * bisher nur per Sichtprüfung abgedeckt.
 *
 * Der Test prüft ausdrücklich auch, dass jeder Knopf **genau die** [ConflictPolicy] meldet, die
 * auf ihm steht — eine vertauschte Zuordnung (Überschreiben ↔ Beide behalten) wäre stiller
 * Datenverlust und im Code-Review leicht zu übersehen.
 */
@RunWith(AndroidJUnit4::class)
class FileBrowserDialogsInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun entry(name: String) = FileEntry(
        file = File("/tmp/$name"),
        name = name,
        isDirectory = false,
        sizeBytes = 0,
        lastModifiedMillis = 0,
        isHidden = false,
        category = FileCategory.OTHER,
    )

    @Test
    fun deleteDialogNamesTheSingleEntry() {
        composeRule.setContent {
            ConfirmDeleteDialog(entries = listOf(entry("rechnung.pdf")), onConfirm = {}, onDismiss = {})
        }
        composeRule
            .onNodeWithText(resources.getString(R.string.dialog_trash_message_one, "rechnung.pdf"))
            .assertIsDisplayed()
    }

    @Test
    fun deleteDialogCountsMultipleEntries() {
        val entries = listOf(entry("a.txt"), entry("b.txt"), entry("c.txt"))
        composeRule.setContent {
            ConfirmDeleteDialog(entries = entries, onConfirm = {}, onDismiss = {})
        }
        composeRule
            .onNodeWithText(
                resources.getQuantityString(R.plurals.dialog_trash_message_many, 3, 3),
            )
            .assertIsDisplayed()
    }

    @Test
    fun deleteDialogConfirmAndCancelAreWiredSeparately() {
        var confirmed = false
        var dismissed = false
        composeRule.setContent {
            ConfirmDeleteDialog(
                entries = listOf(entry("a.txt")),
                onConfirm = { confirmed = true },
                onDismiss = { dismissed = true },
            )
        }
        composeRule.onNodeWithText(resources.getString(R.string.action_cancel)).performClick()
        assertTrue(dismissed)
        assertEquals(false, confirmed)
    }

    /** setContent() darf pro Test nur einmal laufen — deshalb ein Test je Knopf statt einer
     * Schleife. Die drei zusammen decken ab, dass kein Knopf die Policy eines anderen meldet. */
    private fun assertConflictButtonReports(labelRes: Int, expected: ConflictPolicy) {
        var resolved: ConflictPolicy? = null
        composeRule.setContent {
            ConflictResolutionDialog(
                conflictingNames = listOf("bild.jpg"),
                onResolve = { resolved = it },
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText(resources.getString(labelRes)).performClick()
        assertEquals(expected, resolved)
    }

    @Test
    fun overwriteButtonReportsOverwrite() {
        assertConflictButtonReports(R.string.action_overwrite, ConflictPolicy.OVERWRITE)
    }

    @Test
    fun skipButtonReportsSkip() {
        assertConflictButtonReports(R.string.action_skip, ConflictPolicy.SKIP)
    }

    @Test
    fun keepBothButtonReportsKeepBoth() {
        assertConflictButtonReports(R.string.action_keep_both, ConflictPolicy.KEEP_BOTH)
    }

    @Test
    fun conflictDialogListsEveryConflictingName() {
        val names = listOf("eins.jpg", "zwei.jpg", "drei.jpg")
        composeRule.setContent {
            ConflictResolutionDialog(conflictingNames = names, onResolve = {}, onDismiss = {})
        }
        // Bei mehreren Namen steht die Zählung plus die Aufzählung im Text — jeder Name muss
        // sichtbar sein, sonst überschreibt der Nutzer etwas, das er nie gesehen hat.
        val header = resources.getQuantityString(R.plurals.dialog_conflict_message_many, names.size, names.size)
        val expected = header + "\n" + names.joinToString("\n") { "• $it" }
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun conflictDialogCancelDoesNotResolve() {
        var resolved: ConflictPolicy? = null
        var dismissed = false
        composeRule.setContent {
            ConflictResolutionDialog(
                conflictingNames = listOf("bild.jpg"),
                onResolve = { resolved = it },
                onDismiss = { dismissed = true },
            )
        }
        composeRule.onNodeWithText(resources.getString(R.string.action_cancel)).performClick()
        assertTrue(dismissed)
        assertEquals(null, resolved)
    }
}
