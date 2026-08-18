package com.jmail.backend.mail

import com.jmail.backend.auth.RefreshTokenRepository
import com.jmail.backend.config.JmailProperties
import com.jmail.backend.user.MailAccountRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodically refreshes every connected mailbox.
 *
 * Accounts are synced one at a time on a single scheduler thread. That is deliberate: mail
 * providers rate-limit per account *and* per application, and a burst of parallel syncs is
 * the fastest way to get an application throttled for everybody. The loop is also guarded
 * against overlap, so a slow run delays the next one rather than stacking on top of it.
 */
@Component
class SyncScheduler(
    private val properties: JmailProperties,
    private val mailAccountRepository: MailAccountRepository,
    private val mailSyncService: MailSyncService,
    private val refreshTokenRepository: RefreshTokenRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)

    @Scheduled(fixedDelayString = "\${jmail.sync.interval:PT5M}")
    fun syncDueAccounts() {
        if (!properties.sync.enabled) return

        if (!running.compareAndSet(false, true)) {
            log.debug("Previous sync round is still running; skipping this tick")
            return
        }

        try {
            val staleBefore = Instant.now().minus(properties.sync.interval)
            val due = mailAccountRepository.findAccountsDueForSync(staleBefore)
            if (due.isEmpty()) return

            log.debug("Syncing {} account(s)", due.size)
            due.forEach { account ->
                // One account's failure is contained so the rest of the round still runs.
                runCatching { mailSyncService.syncAccount(account) }
                    .onFailure { log.error("Unhandled failure syncing account {}", account.id, it) }
            }
        } finally {
            running.set(false)
        }
    }

    /** Expired refresh tokens serve no purpose and would grow the table without bound. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    fun pruneExpiredRefreshTokens() {
        val removed = refreshTokenRepository.deleteExpiredBefore(Instant.now())
        if (removed > 0) log.info("Pruned {} expired refresh tokens", removed)
    }
}
