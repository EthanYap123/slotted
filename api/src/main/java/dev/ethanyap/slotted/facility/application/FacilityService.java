package dev.ethanyap.slotted.facility.application;

import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.common.IfMatch;
import dev.ethanyap.slotted.facility.domain.Facility;
import dev.ethanyap.slotted.facility.infrastructure.FacilityRepository;
import dev.ethanyap.slotted.user.domain.Role;
import dev.ethanyap.slotted.user.infrastructure.UserRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Facilities, their opening hours and managers (PRD MG-2, AD-2). Access is checked by callers. */
@Service
public class FacilityService {

  private final FacilityRepository facilities;
  private final UserRepository users;

  public FacilityService(FacilityRepository facilities, UserRepository users) {
    this.facilities = facilities;
    this.users = users;
  }

  @Transactional(readOnly = true)
  public List<Facility> list() {
    return facilities.findAll(Sort.by("name"));
  }

  @Transactional(readOnly = true)
  public Facility get(UUID facilityId) {
    return facilities
        .findById(facilityId)
        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No facility with this ID."));
  }

  @Transactional
  public Facility create(String name, String description, String location) {
    var facility = new Facility(name, description, location);
    if (facilities.existsByNameIgnoreCase(facility.getName())) {
      throw nameTaken();
    }
    return saveUniquely(facility);
  }

  @Transactional
  public Facility update(UUID facilityId, Long expectedVersion, FacilityChanges changes) {
    var facility = get(facilityId);
    IfMatch.check(expectedVersion, facility.getVersion());
    if (changes.name() != null) {
      facility.rename(changes.name());
      if (facilities.existsByNameIgnoreCaseAndIdNot(facility.getName(), facilityId)) {
        throw nameTaken();
      }
    }
    if (changes.description() != null) {
      facility.changeDescription(changes.description());
    }
    if (changes.location() != null) {
      facility.changeLocation(changes.location());
    }
    return saveUniquely(facility);
  }

  /** Sets who manages the facility. Every user must exist and have the Manager role. */
  @Transactional
  public void replaceManagers(UUID facilityId, Collection<UUID> userIds) {
    var facility = get(facilityId);
    var ids = new HashSet<>(userIds);
    var found = users.findAllById(ids);
    if (found.size() != ids.size()) {
      throw new ApiException(ErrorCode.NOT_FOUND, "One or more users do not exist.");
    }
    for (var user : found) {
      if (user.getRole() != Role.MANAGER) {
        throw new ApiException(
            ErrorCode.NOT_A_MANAGER,
            user.getEmail() + " is not a manager. Change their role to Manager first.");
      }
    }
    facility.replaceManagers(ids);
  }

  private Facility saveUniquely(Facility facility) {
    try {
      return facilities.saveAndFlush(facility);
    } catch (DataIntegrityViolationException e) {
      // Another request took the name between the check and the save.
      throw nameTaken();
    }
  }

  private static ApiException nameTaken() {
    return new ApiException(ErrorCode.NAME_TAKEN, "There is already a facility with this name.");
  }

  /** Requested changes; null fields are left alone and blank text clears an optional field. */
  public record FacilityChanges(String name, String description, String location) {}
}
