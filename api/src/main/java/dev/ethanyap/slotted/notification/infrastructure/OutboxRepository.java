package dev.ethanyap.slotted.notification.infrastructure;

import dev.ethanyap.slotted.notification.domain.EmailContent;
import dev.ethanyap.slotted.notification.domain.OutboxEmail;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** The email outbox. */
public interface OutboxRepository extends JpaRepository<OutboxEmail, Long> {

  /**
   * Locks one email for sending, or returns empty straight away if another sender has it ({@code
   * SKIP LOCKED}), so the same email is never sent twice at once.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("select e from OutboxEmail e where e.id = :id")
  Optional<OutboxEmail> tryLockById(Long id);

  /** Emails due for a try, oldest first. */
  @Query(
      "select e.id from OutboxEmail e"
          + " where e.status = dev.ethanyap.slotted.notification.domain.EmailStatus.PENDING"
          + " and e.nextAttemptAt <= :now order by e.nextAttemptAt, e.id")
  List<Long> findDueIds(Instant now, Limit limit);

  @Query(
      "select count(e) from OutboxEmail e"
          + " where e.status = dev.ethanyap.slotted.notification.domain.EmailStatus.SENT"
          + " and e.sentAt >= :since")
  long countSentSince(Instant since);

  /**
   * Queues an email with a dedupe key, or does nothing if one with that key exists. Done in one
   * statement, so two transactions noticing the same thing at once cannot both insert it, and the
   * one that loses does not fail: it would otherwise roll back the change it was part of.
   */
  @Query(
      value =
          """
          INSERT INTO email_outbox
              (to_address, to_name, kind, subject, text_body, html_body, dedupe_key,
               next_attempt_at, created_at)
          VALUES (:#{#email.toAddress}, :#{#email.toName}, :#{#email.kind},
                  :#{#email.subject}, :#{#email.textBody}, :#{#email.htmlBody},
                  :#{#email.dedupeKey}, :now, :now)
          ON CONFLICT (dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING
          RETURNING id
          """,
      nativeQuery = true)
  List<Long> insertUnlessQueued(@Param("email") EmailContent email, @Param("now") Instant now);

  /** Deletes emails that are finished with and older than {@code cutoff}; returns how many. */
  @Modifying
  @Query(
      "delete from OutboxEmail e"
          + " where e.status <> dev.ethanyap.slotted.notification.domain.EmailStatus.PENDING"
          + " and e.createdAt < :cutoff")
  int deleteFinishedBefore(Instant cutoff);
}
