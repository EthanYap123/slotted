package dev.ethanyap.slotted.booking.domain;

import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A period when one room, or a whole facility if {@code roomId} is null, cannot be booked (BR-30).
 * It lives with bookings because creating one cancels the bookings inside it.
 */
@Entity
@Table(name = "blackout")
public class Blackout {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private UUID facilityId;
  private UUID roomId;
  private Instant startAt;
  private Instant endAt;
  private String reason;
  private UUID createdBy;

  @CreationTimestamp private Instant createdAt;

  protected Blackout() {}

  public Blackout(
      UUID facilityId, UUID roomId, Instant startAt, Instant endAt, String reason, UUID createdBy) {
    this.facilityId = facilityId;
    this.roomId = roomId;
    this.startAt = startAt;
    this.endAt = endAt;
    this.reason = reason.strip();
    this.createdBy = createdBy;
  }

  /** Checks a new blackout makes sense; one that is already over would do nothing. */
  public void checkNewAt(Instant now) {
    if (!startAt.isBefore(endAt)) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "A blackout must end after it starts.");
    }
    if (!endAt.isAfter(now)) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "A blackout must end in the future.");
    }
  }

  public boolean covers(UUID room, Instant from, Instant to) {
    return (roomId == null || roomId.equals(room)) && startAt.isBefore(to) && endAt.isAfter(from);
  }

  /** The values the audit log keeps for a blackout (BR-32). */
  public Map<String, Object> auditState() {
    var state = new LinkedHashMap<String, Object>();
    state.put("facilityId", facilityId);
    state.put("roomId", roomId);
    state.put("startAt", startAt);
    state.put("endAt", endAt);
    state.put("reason", reason);
    state.put("createdBy", createdBy);
    return state;
  }

  public UUID getId() {
    return id;
  }

  public UUID getFacilityId() {
    return facilityId;
  }

  public UUID getRoomId() {
    return roomId;
  }

  public Instant getStartAt() {
    return startAt;
  }

  public Instant getEndAt() {
    return endAt;
  }

  public String getReason() {
    return reason;
  }
}
