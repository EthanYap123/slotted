package dev.ethanyap.slotted.booking.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** Groups the occurrences of a recurring booking (BR-20). Each occurrence is a {@link Booking}. */
@Entity
@Table(name = "booking_series")
public class BookingSeries {

  /** How often a series repeats. */
  public enum Frequency {
    DAILY,
    WEEKLY
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private UUID userId;
  private UUID roomId;

  @Enumerated(EnumType.STRING)
  private Frequency frequency;

  private short occurrencesRequested;
  private Instant cancelledAt;

  @CreationTimestamp private Instant createdAt;

  protected BookingSeries() {}

  public BookingSeries(UUID userId, UUID roomId, Frequency frequency, int occurrencesRequested) {
    this.userId = userId;
    this.roomId = roomId;
    this.frequency = frequency;
    this.occurrencesRequested = (short) occurrencesRequested;
  }

  public void markCancelled(Instant now) {
    cancelledAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getRoomId() {
    return roomId;
  }

  public Frequency getFrequency() {
    return frequency;
  }

  public int getOccurrencesRequested() {
    return occurrencesRequested;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }
}
