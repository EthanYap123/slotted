package dev.ethanyap.slotted.room.domain;

import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A bookable space in a facility (PRD MG-1). Rooms are deactivated, never deleted. */
@Entity
@Table(name = "room")
public class Room {

  public static final int MIN_CAPACITY = 1;
  public static final int MAX_CAPACITY = 500;
  public static final int DEFAULT_MAX_BOOKING_MINUTES = 120;
  public static final int MIN_BOOKING_MINUTES = 30;
  public static final int LONGEST_MAX_BOOKING_MINUTES = 240;

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private UUID facilityId;
  private String name;

  @Enumerated(EnumType.STRING)
  private RoomType type;

  private int capacity;
  private int maxBookingMinutes;
  private boolean active;

  @Column(name = "checkin_token")
  private String checkInToken;

  @CreationTimestamp private Instant createdAt;
  @UpdateTimestamp private Instant updatedAt;
  @Version private long version;

  protected Room() {}

  public Room(
      UUID facilityId,
      String name,
      RoomType type,
      int capacity,
      Integer maxBookingMinutes,
      String checkInToken) {
    this.facilityId = facilityId;
    rename(name);
    changeType(type);
    changeCapacity(capacity);
    changeMaxBookingMinutes(
        maxBookingMinutes == null ? DEFAULT_MAX_BOOKING_MINUTES : maxBookingMinutes);
    this.active = true;
    this.checkInToken = checkInToken;
  }

  /** A new secret for the room's QR code; the old one stops working (MG-5). */
  public void replaceCheckInToken(String token) {
    this.checkInToken = token;
  }

  public void rename(String name) {
    if (name == null || name.isBlank()) {
      throw invalid("A room needs a name.");
    }
    this.name = name.strip();
  }

  public void changeType(RoomType type) {
    if (type == null) {
      throw invalid("A room needs a type.");
    }
    this.type = type;
  }

  public void changeCapacity(int capacity) {
    if (capacity < MIN_CAPACITY || capacity > MAX_CAPACITY) {
      throw invalid("Capacity must be between " + MIN_CAPACITY + " and " + MAX_CAPACITY + ".");
    }
    this.capacity = capacity;
  }

  /** The longest booking allowed in this room: 30 to 240 minutes in steps of 30 (BR-2). */
  public void changeMaxBookingMinutes(int minutes) {
    if (minutes < MIN_BOOKING_MINUTES
        || minutes > LONGEST_MAX_BOOKING_MINUTES
        || minutes % MIN_BOOKING_MINUTES != 0) {
      throw invalid("Maximum booking length must be 30 to 240 minutes, in steps of 30.");
    }
    this.maxBookingMinutes = minutes;
  }

  /** Inactive rooms cannot be booked; existing bookings stay (BR-10). */
  public void setActive(boolean active) {
    this.active = active;
  }

  private static ApiException invalid(String detail) {
    return new ApiException(ErrorCode.VALIDATION_FAILED, detail);
  }

  /**
   * The values the audit log keeps for a room (BR-32). The check-in token is a secret, so it is
   * left out.
   */
  public Map<String, Object> auditState() {
    var state = new LinkedHashMap<String, Object>();
    state.put("facilityId", facilityId);
    state.put("name", name);
    state.put("type", type);
    state.put("capacity", capacity);
    state.put("maxBookingMinutes", maxBookingMinutes);
    state.put("active", active);
    return state;
  }

  public UUID getId() {
    return id;
  }

  public UUID getFacilityId() {
    return facilityId;
  }

  public String getName() {
    return name;
  }

  public RoomType getType() {
    return type;
  }

  public int getCapacity() {
    return capacity;
  }

  public int getMaxBookingMinutes() {
    return maxBookingMinutes;
  }

  public boolean isActive() {
    return active;
  }

  public String getCheckInToken() {
    return checkInToken;
  }

  public long getVersion() {
    return version;
  }
}
