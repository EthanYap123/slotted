package dev.ethanyap.slotted.notification.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One email waiting in the outbox, or a record of how it went (ADR-0004). */
@Entity
@Table(name = "email_outbox")
public class OutboxEmail {

  private static final int MAX_ERROR_LENGTH = 500;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String toAddress;
  private String toName;
  private String kind;
  private String subject;
  private String textBody;
  private String htmlBody;
  private String dedupeKey;

  @Enumerated(EnumType.STRING)
  private EmailStatus status;

  private short attempts;
  private Instant nextAttemptAt;
  private String lastError;
  private Instant createdAt;
  private Instant sentAt;

  protected OutboxEmail() {}

  public OutboxEmail(EmailContent content, Instant now) {
    this.toAddress = content.toAddress();
    this.toName = content.toName();
    this.kind = content.kind();
    this.subject = content.subject();
    this.textBody = content.textBody();
    this.htmlBody = content.htmlBody();
    this.dedupeKey = content.dedupeKey();
    this.status = EmailStatus.PENDING;
    this.nextAttemptAt = now;
    this.createdAt = now;
  }

  /** Whether it is waiting and its next try is due. */
  public boolean isDue(Instant now) {
    return status == EmailStatus.PENDING && !nextAttemptAt.isAfter(now);
  }

  public void markSent(Instant now) {
    attempts++;
    status = EmailStatus.SENT;
    sentAt = now;
    lastError = null;
  }

  public void markSkipped() {
    status = EmailStatus.SKIPPED;
  }

  /**
   * Records a failed try. It is tried again after a growing wait, until the attempts run out or the
   * provider refused it for good.
   */
  public void markFailed(String error, boolean permanent, Instant now) {
    attempts++;
    lastError =
        error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
    if (permanent || attempts >= DeliveryRules.MAX_ATTEMPTS) {
      status = EmailStatus.FAILED;
    } else {
      nextAttemptAt = now.plus(DeliveryRules.retryDelay(attempts));
    }
  }

  public Long getId() {
    return id;
  }

  public String getToAddress() {
    return toAddress;
  }

  public EmailContent content() {
    return new EmailContent(toAddress, toName, kind, subject, textBody, htmlBody, dedupeKey);
  }

  public EmailStatus getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }
}
