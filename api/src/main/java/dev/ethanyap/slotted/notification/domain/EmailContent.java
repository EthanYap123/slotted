package dev.ethanyap.slotted.notification.domain;

/**
 * A finished email: who it goes to and what it says.
 *
 * @param kind what the email is about, for logs and tests, for example {@code BOOKING_CONFIRMED}
 * @param dedupeKey if set, an email with the same key is only ever queued once
 */
public record EmailContent(
    String toAddress,
    String toName,
    String kind,
    String subject,
    String textBody,
    String htmlBody,
    String dedupeKey) {}
