package dev.ethanyap.slotted.facility.domain;

import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** A venue with its own opening hours and managers, for example "Library Level 3". */
@Entity
@Table(name = "facility")
public class Facility {

  public static final String DEFAULT_TIME_ZONE = "Asia/Singapore";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private String name;
  private String description;
  private String location;
  private String timeZone;

  /** Always needed with the facility and at most seven rows, so loaded eagerly. */
  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "opening_hours", joinColumns = @JoinColumn(name = "facility_id"))
  private Set<OpeningPeriod> openingHours = new HashSet<>();

  @ElementCollection
  @CollectionTable(name = "facility_manager", joinColumns = @JoinColumn(name = "facility_id"))
  @Column(name = "user_id")
  private Set<UUID> managerIds = new HashSet<>();

  @CreationTimestamp private Instant createdAt;
  @UpdateTimestamp private Instant updatedAt;
  @Version private long version;

  protected Facility() {}

  public Facility(String name, String description, String location) {
    rename(name);
    changeDescription(description);
    changeLocation(location);
    this.timeZone = DEFAULT_TIME_ZONE;
  }

  public void rename(String name) {
    if (name == null || name.isBlank()) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "A facility needs a name.");
    }
    this.name = name.strip();
  }

  /** A blank value clears the description. */
  public void changeDescription(String description) {
    this.description = blankToNull(description);
  }

  /** A blank value clears the location. */
  public void changeLocation(String location) {
    this.location = blankToNull(location);
  }

  /** Replaces the whole week. Days not listed are closed. */
  public void replaceOpeningHours(Collection<OpeningPeriod> periods) {
    checkOneEntryPerDay(periods);
    openingHours.clear();
    openingHours.addAll(periods);
  }

  /** A week of opening hours may list each day at most once. */
  public static void checkOneEntryPerDay(Collection<OpeningPeriod> periods) {
    var days = new HashSet<DayOfWeek>();
    for (var period : periods) {
      if (!days.add(period.dayOfWeek())) {
        throw new ApiException(
            ErrorCode.VALIDATION_FAILED, "Each day can appear only once in opening hours.");
      }
    }
  }

  public void replaceManagers(Collection<UUID> userIds) {
    managerIds.clear();
    managerIds.addAll(userIds);
  }

  /** Opening hours from Monday to Sunday. */
  public List<OpeningPeriod> getOpeningHours() {
    return openingHours.stream().sorted(Comparator.comparing(OpeningPeriod::dayOfWeek)).toList();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getLocation() {
    return location;
  }

  public String getTimeZone() {
    return timeZone;
  }

  public Set<UUID> getManagerIds() {
    return Set.copyOf(managerIds);
  }

  public long getVersion() {
    return version;
  }
}
