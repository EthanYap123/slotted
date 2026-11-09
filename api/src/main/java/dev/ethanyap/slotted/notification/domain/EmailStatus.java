package dev.ethanyap.slotted.notification.domain;

/** Where an outbox email stands. */
public enum EmailStatus {
  /** Waiting to be sent, or to be tried again. */
  PENDING,
  SENT,
  /** Refused by the provider, or out of attempts. */
  FAILED,
  /** Addressed to a reserved demo domain, so never sent. */
  SKIPPED
}
