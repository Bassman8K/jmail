package com.jmail.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.jmail.app.ui.mailbox.MessageRow
import com.jmail.app.ui.theme.JMailTheme
import com.jmail.shared.model.Category
import com.jmail.shared.model.EmailAddress
import com.jmail.shared.model.MessageSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI tests that drive the real message row.
 *
 * These use the same testing API as Jetpack Compose's `createComposeRule` and run on the
 * JVM through `:composeApp:desktopTest`, so every contributor exercises the UI without an
 * emulator. The identical sources also run as instrumented tests on Android via
 * `:composeApp:connectedAndroidTest`.
 *
 * What is asserted here is what a *user* experiences: that the sender and subject are on
 * screen, that tapping the row opens the message, that the star is operable, and that a
 * screen-reader user is told everything a sighted user can see.
 */
@OptIn(ExperimentalTestApi::class)
class MessageRowUiTest {

    private fun message(
        subject: String = "Quarterly review",
        isRead: Boolean = false,
        isStarred: Boolean = false,
        hasAttachments: Boolean = false,
    ) = MessageSummary(
        id = "m1",
        accountId = "acc-1",
        folderId = "fol-1",
        threadId = "t1",
        subject = subject,
        snippet = "Agenda attached for Thursday",
        from = EmailAddress("priya@example.com", "Priya Raman"),
        receivedAt = "2024-04-25T10:00:00Z",
        isRead = isRead,
        isStarred = isStarred,
        hasAttachments = hasAttachments,
    )

    @Test
    fun the_row_shows_who_it_is_from_what_it_is_about_and_a_preview() = runComposeUiTest {
        setContent {
            JMailTheme {
                Box {
                    MessageRow(
                        message = message(),
                        isSelected = false,
                        isChecked = false,
                        selectionMode = false,
                        category = null,
                        onClick = {},
                        onToggleChecked = {},
                        onToggleStar = {},
                    )
                }
            }
        }

        onNodeWithText("Priya Raman").assertIsDisplayed()
        onNodeWithText("Quarterly review").assertIsDisplayed()
        onNodeWithText("Agenda attached for Thursday").assertIsDisplayed()
    }

    @Test
    fun tapping_the_row_opens_the_message() = runComposeUiTest {
        var opened = 0

        setContent {
            JMailTheme {
                MessageRow(
                    message = message(),
                    isSelected = false,
                    isChecked = false,
                    selectionMode = false,
                    category = null,
                    onClick = { opened++ },
                    onToggleChecked = {},
                    onToggleStar = {},
                )
            }
        }

        onNodeWithText("Quarterly review").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun the_star_is_reachable_and_labelled_by_its_current_state() = runComposeUiTest {
        var starToggles = 0

        setContent {
            JMailTheme {
                MessageRow(
                    message = message(isStarred = false),
                    isSelected = false,
                    isChecked = false,
                    selectionMode = false,
                    category = null,
                    onClick = {},
                    onToggleChecked = {},
                    onToggleStar = { starToggles++ },
                )
            }
        }

        // The label says what the action does, not what the state is — which is what a
        // screen reader announces when the control is focused.
        onNodeWithContentDescription("Add star").performClick()

        assertEquals(1, starToggles)
    }

    @Test
    fun a_starred_message_offers_to_remove_the_star() = runComposeUiTest {
        setContent {
            JMailTheme {
                MessageRow(
                    message = message(isStarred = true),
                    isSelected = false,
                    isChecked = false,
                    selectionMode = false,
                    category = null,
                    onClick = {},
                    onToggleChecked = {},
                    onToggleStar = {},
                )
            }
        }

        onNodeWithContentDescription("Remove star").assertIsDisplayed()
    }

    @Test
    fun selection_mode_replaces_the_avatar_with_a_labelled_checkbox() = runComposeUiTest {
        var checked = 0

        setContent {
            JMailTheme {
                MessageRow(
                    message = message(),
                    isSelected = false,
                    isChecked = false,
                    selectionMode = true,
                    category = null,
                    onClick = {},
                    onToggleChecked = { checked++ },
                    onToggleStar = {},
                )
            }
        }

        onNodeWithContentDescription("Select message").performClick()

        assertEquals(1, checked)
    }

    @Test
    fun a_screen_reader_hears_the_whole_row_as_one_item() = runComposeUiTest {
        setContent {
            JMailTheme {
                MessageRow(
                    message = message(hasAttachments = true, isStarred = true),
                    isSelected = false,
                    isChecked = false,
                    selectionMode = false,
                    category = Category(id = "c1", key = "receipts", name = "Receipts"),
                    onClick = {},
                    onToggleChecked = {},
                    onToggleStar = {},
                )
            }
        }

        // Merged into a single description rather than six fragments, so the list can be
        // scanned by ear at the same speed as by eye.
        // The row publishes a contentDescription rather than relying on its child Text
        // nodes, which is what makes it read as one item instead of six.
        val nodes = onAllNodes(
            hasContentDescription("Unread message from Priya Raman", substring = true),
        ).fetchSemanticsNodes()

        assertTrue(nodes.isNotEmpty(), "the row should expose one merged description")
    }

    @Test
    fun a_message_with_no_subject_still_reads_sensibly() = runComposeUiTest {
        setContent {
            JMailTheme {
                MessageRow(
                    message = message(subject = ""),
                    isSelected = false,
                    isChecked = false,
                    selectionMode = false,
                    category = null,
                    onClick = {},
                    onToggleChecked = {},
                    onToggleStar = {},
                )
            }
        }

        onNodeWithText("(no subject)").assertIsDisplayed()
    }
}
