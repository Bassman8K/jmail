package com.jmail.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.jmail.app.ui.components.EmptyState
import com.jmail.app.ui.components.ErrorState
import com.jmail.app.ui.mailbox.MessageList
import com.jmail.app.ui.reader.ReaderPane
import com.jmail.app.ui.theme.JMailTheme
import com.jmail.shared.model.Attachment
import com.jmail.shared.model.EmailAddress
import com.jmail.shared.model.MessageDetail
import com.jmail.shared.model.MessageSummary
import com.jmail.shared.network.ApiError
import com.jmail.shared.repository.MailboxQuery
import com.jmail.shared.state.MailboxUiState
import com.jmail.shared.state.ReaderUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UI tests for the states a user actually spends time in: nothing yet, something went
 * wrong, and reading a message.
 *
 * Empty and error states are where products usually go quiet and unhelpful, so each one is
 * asserted to explain *why* it is empty and to offer the right next step — including the
 * rule that a retry button appears only when retrying could plausibly work.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenStateUiTest {

    private fun summary(id: String, subject: String) = MessageSummary(
        id = id,
        accountId = "acc-1",
        folderId = "fol-1",
        threadId = "t-$id",
        subject = subject,
        snippet = "Preview of $subject",
        from = EmailAddress("priya@example.com", "Priya Raman"),
        receivedAt = "2024-04-25T10:00:00Z",
    )

    // ---- empty states ----------------------------------------------------

    @Test
    fun an_empty_inbox_is_framed_as_good_news_not_an_error() = runComposeUiTest {
        setContent {
            JMailTheme {
                MessageList(
                    state = MailboxUiState(messages = emptyList()),
                    onOpenMessage = {},
                    onToggleChecked = {},
                    onToggleStar = {},
                    onLoadMore = {},
                    onRetry = {},
                    onClearFilters = {},
                )
            }
        }

        onNodeWithText("You're all caught up").assertIsDisplayed()
    }

    @Test
    fun an_empty_search_suggests_what_to_try_instead() = runComposeUiTest {
        setContent {
            JMailTheme {
                MessageList(
                    state = MailboxUiState(query = MailboxQuery(searchQuery = "zzzz")),
                    onOpenMessage = {},
                    onToggleChecked = {},
                    onToggleStar = {},
                    onLoadMore = {},
                    onRetry = {},
                    onClearFilters = {},
                )
            }
        }

        onNodeWithText("No results for \"zzzz\"").assertIsDisplayed()
        onNodeWithText("Check the spelling, try a sender's address, or search for a word from the message body.")
            .assertIsDisplayed()
    }

    @Test
    fun an_empty_filtered_list_offers_to_clear_the_filter() = runComposeUiTest {
        var cleared = 0

        setContent {
            JMailTheme {
                MessageList(
                    state = MailboxUiState(query = MailboxQuery(unreadOnly = true)),
                    onOpenMessage = {},
                    onToggleChecked = {},
                    onToggleStar = {},
                    onLoadMore = {},
                    onRetry = {},
                    onClearFilters = { cleared++ },
                )
            }
        }

        onNodeWithText("Nothing matches these filters").assertIsDisplayed()
        onNodeWithText("Clear filters").performClick()

        assertEquals(1, cleared)
    }

    @Test
    fun a_custom_empty_state_renders_its_action() = runComposeUiTest {
        var acted = 0

        setContent {
            JMailTheme {
                EmptyState(
                    title = "No drafts",
                    description = "Messages you start but do not send will wait here.",
                    actionLabel = "Write one",
                    onAction = { acted++ },
                )
            }
        }

        onNodeWithText("Write one").performClick()
        assertEquals(1, acted)
    }

    // ---- error states ----------------------------------------------------

    @Test
    fun being_offline_says_so_plainly_and_offers_a_retry() = runComposeUiTest {
        var retried = 0

        setContent {
            JMailTheme {
                ErrorState(error = ApiError.network(), onRetry = { retried++ })
            }
        }

        onNodeWithText("You're offline").assertIsDisplayed()
        onNodeWithText("Try again").performClick()

        assertEquals(1, retried)
    }

    @Test
    fun an_error_that_retrying_cannot_fix_does_not_offer_a_retry() = runComposeUiTest {
        setContent {
            JMailTheme {
                ErrorState(
                    error = ApiError(
                        kind = ApiError.Kind.CLIENT,
                        code = "message_not_found",
                        userMessage = "That message was not found.",
                    ),
                    onRetry = {},
                )
            }
        }

        onNodeWithText("That message was not found.").assertIsDisplayed()
        // Offering a button that will fail identically is worse than offering nothing.
        assertTrue(onAllNodes(hasText("Try again")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun a_reconnect_error_names_the_situation_rather_than_saying_unauthorised() = runComposeUiTest {
        setContent {
            JMailTheme {
                ErrorState(
                    error = ApiError(
                        kind = ApiError.Kind.REAUTHENTICATION_REQUIRED,
                        code = "reauthentication_required",
                        userMessage = "Your Google account needs to be reconnected",
                    ),
                )
            }
        }

        onNodeWithText("Reconnect this account").assertIsDisplayed()
    }

    // ---- list ------------------------------------------------------------

    @Test
    fun the_list_renders_its_rows_and_opens_the_one_that_is_tapped() = runComposeUiTest {
        var opened: String? = null

        setContent {
            JMailTheme {
                MessageList(
                    state = MailboxUiState(
                        messages = listOf(
                            summary("m1", "First message"),
                            summary("m2", "Second message"),
                        ),
                    ),
                    onOpenMessage = { opened = it },
                    onToggleChecked = {},
                    onToggleStar = {},
                    onLoadMore = {},
                    onRetry = {},
                    onClearFilters = {},
                )
            }
        }

        onNodeWithText("First message").assertIsDisplayed()
        onNodeWithText("Second message").assertIsDisplayed()
        onNodeWithText("Second message").performClick()

        assertEquals("m2", opened)
    }

    @Test
    fun a_failed_first_load_shows_the_error_rather_than_an_empty_list() = runComposeUiTest {
        setContent {
            JMailTheme {
                MessageList(
                    state = MailboxUiState(messages = emptyList(), error = ApiError.network()),
                    onOpenMessage = {},
                    onToggleChecked = {},
                    onToggleStar = {},
                    onLoadMore = {},
                    onRetry = {},
                    onClearFilters = {},
                )
            }
        }

        onNodeWithText("You're offline").assertIsDisplayed()
    }

    // ---- reader ----------------------------------------------------------

    private val detail = MessageDetail(
        id = "m1",
        accountId = "acc-1",
        folderId = "fol-1",
        threadId = "t1",
        subject = "Thursday's design review",
        from = EmailAddress("priya@example.com", "Priya Raman"),
        to = listOf(EmailAddress("ada@example.com", "Ada")),
        bodyText = "Shall we cut the spacious density option?",
        sentAt = "2024-04-25T10:00:00Z",
        receivedAt = "2024-04-25T10:00:00Z",
        attachments = listOf(Attachment("a1", "agenda.pdf", "application/pdf", 84_213)),
    )

    @Test
    fun the_reader_shows_the_subject_sender_body_and_attachments() = runComposeUiTest {
        setContent {
            JMailTheme {
                ReaderPane(
                    state = ReaderUiState(messageId = "m1", message = detail),
                    onClose = {}, onArchive = {}, onTrash = {}, onSpam = {}, onToggleStar = {},
                    onReply = { _, _ -> }, onForward = {}, onLoadRemoteImages = {},
                    onRetry = {}, onOpenLink = {},
                )
            }
        }

        onNodeWithText("Thursday's design review").assertIsDisplayed()
        onNodeWithText("Priya Raman").assertIsDisplayed()
        onNodeWithText("Shall we cut the spacious density option?").assertIsDisplayed()
        onNodeWithText("agenda.pdf").assertIsDisplayed()
        onNodeWithText("1 attachment").assertIsDisplayed()
    }

    @Test
    fun nothing_selected_invites_the_user_to_pick_a_message() = runComposeUiTest {
        setContent {
            JMailTheme {
                ReaderPane(
                    state = ReaderUiState(),
                    onClose = {}, onArchive = {}, onTrash = {}, onSpam = {}, onToggleStar = {},
                    onReply = { _, _ -> }, onForward = {}, onLoadRemoteImages = {},
                    onRetry = {}, onOpenLink = {},
                )
            }
        }

        onNodeWithText("Nothing selected").assertIsDisplayed()
    }

    @Test
    fun blocked_images_are_explained_in_terms_of_what_it_protects() = runComposeUiTest {
        var loadRequested = 0

        setContent {
            JMailTheme {
                ReaderPane(
                    state = ReaderUiState(
                        messageId = "m1",
                        message = detail.copy(hasBlockedImages = true),
                    ),
                    onClose = {}, onArchive = {}, onTrash = {}, onSpam = {}, onToggleStar = {},
                    onReply = { _, _ -> }, onForward = {},
                    onLoadRemoteImages = { loadRequested++ },
                    onRetry = {}, onOpenLink = {},
                )
            }
        }

        onNodeWithText(
            "Images in this message were not loaded, so the sender cannot tell that you opened it.",
        ).assertIsDisplayed()

        onNodeWithText("Show images").performClick()
        assertEquals(1, loadRequested)
    }

    @Test
    fun replying_is_offered_and_reply_all_only_when_there_are_others() = runComposeUiTest {
        var repliedAll: Boolean? = null

        setContent {
            JMailTheme {
                ReaderPane(
                    state = ReaderUiState(
                        messageId = "m1",
                        message = detail.copy(
                            to = listOf(EmailAddress("ada@example.com"), EmailAddress("tom@example.com")),
                        ),
                    ),
                    onClose = {}, onArchive = {}, onTrash = {}, onSpam = {}, onToggleStar = {},
                    onReply = { _, all -> repliedAll = all },
                    onForward = {}, onLoadRemoteImages = {}, onRetry = {}, onOpenLink = {},
                )
            }
        }

        onNodeWithText("Reply all").performClick()
        assertEquals(true, repliedAll)
    }

    @Test
    fun a_single_recipient_message_does_not_offer_reply_all() = runComposeUiTest {
        setContent {
            JMailTheme {
                ReaderPane(
                    state = ReaderUiState(messageId = "m1", message = detail),
                    onClose = {}, onArchive = {}, onTrash = {}, onSpam = {}, onToggleStar = {},
                    onReply = { _, _ -> }, onForward = {}, onLoadRemoteImages = {},
                    onRetry = {}, onOpenLink = {},
                )
            }
        }

        onNodeWithText("Reply").assertIsDisplayed()
        assertTrue(onAllNodes(hasText("Reply all")).fetchSemanticsNodes().isEmpty())
    }
}
