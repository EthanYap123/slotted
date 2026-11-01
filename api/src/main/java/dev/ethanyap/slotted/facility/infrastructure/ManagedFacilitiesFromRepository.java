package dev.ethanyap.slotted.facility.infrastructure;

import dev.ethanyap.slotted.user.application.ManagedFacilities;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ManagedFacilitiesFromRepository implements ManagedFacilities {

  private final FacilityRepository facilities;

  public ManagedFacilitiesFromRepository(FacilityRepository facilities) {
    this.facilities = facilities;
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> facilityIdsManagedBy(UUID userId) {
    return facilities.findIdsManagedBy(userId);
  }
}
