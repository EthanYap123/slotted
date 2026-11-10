package dev.ethanyap.slotted.analytics.application;

import dev.ethanyap.slotted.analytics.domain.Utilisation;
import dev.ethanyap.slotted.analytics.domain.Utilisation.Report;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.facility.domain.Facility;
import dev.ethanyap.slotted.facility.infrastructure.FacilityRepository;
import dev.ethanyap.slotted.user.domain.Role;
import dev.ethanyap.slotted.user.domain.User;
import dev.ethanyap.slotted.user.infrastructure.UserRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Room utilisation and attendance (PRD AD-4). Admins see every facility; managers see only the
 * facilities they manage. Roles are read from the database, so a change applies at once.
 */
@Service
public class UtilisationService {

  /** The longest range one report covers, about a quarter of a year. */
  public static final int MAX_DAYS = 92;

  private final UserRepository users;
  private final FacilityRepository facilities;
  private final UsageData data;

  UtilisationService(UserRepository users, FacilityRepository facilities, UsageData data) {
    this.users = users;
    this.facilities = facilities;
    this.data = data;
  }

  /**
   * The report from {@code fromDate} to {@code toDate}, both inclusive, for one facility or, when
   * {@code facilityId} is null, every facility the caller may see.
   */
  @Transactional(readOnly = true)
  public Report report(UUID actorId, UUID facilityId, LocalDate fromDate, LocalDate toDate) {
    if (toDate.isBefore(fromDate)) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "toDate must not be before fromDate.");
    }
    if (ChronoUnit.DAYS.between(fromDate, toDate) + 1 > MAX_DAYS) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED, "A report can cover at most " + MAX_DAYS + " days.");
    }
    var inputs = data.load(facilitiesFor(actorId, facilityId), fromDate, toDate);
    return Utilisation.calculate(fromDate, toDate, inputs.rooms(), inputs.bookings());
  }

  private List<UUID> facilitiesFor(UUID actorId, UUID facilityId) {
    var actor = users.findById(actorId).filter(User::isActive);
    var role = actor.map(User::getRole).orElse(Role.STUDENT);
    if (role == Role.ADMIN) {
      if (facilityId == null) {
        return facilities.findAll().stream().map(Facility::getId).toList();
      }
      if (!facilities.existsById(facilityId)) {
        throw new ApiException(ErrorCode.NOT_FOUND, "No facility with this ID.");
      }
      return List.of(facilityId);
    }
    if (role != Role.MANAGER) {
      throw new ApiException(ErrorCode.FORBIDDEN);
    }
    var managed = facilities.findIdsManagedBy(actorId);
    if (facilityId == null) {
      return managed;
    }
    if (!managed.contains(facilityId)) {
      throw new ApiException(ErrorCode.FORBIDDEN, "You can only see facilities you manage.");
    }
    return List.of(facilityId);
  }
}
