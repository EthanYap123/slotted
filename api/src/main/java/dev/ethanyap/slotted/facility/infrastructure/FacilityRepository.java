package dev.ethanyap.slotted.facility.infrastructure;

import dev.ethanyap.slotted.facility.domain.Facility;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FacilityRepository extends JpaRepository<Facility, UUID> {

  boolean existsByNameIgnoreCase(String name);

  boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

  @Query("select f.id from Facility f join f.managerIds m where m = :userId order by f.name")
  List<UUID> findIdsManagedBy(UUID userId);

  @Query(
      "select count(f) > 0 from Facility f join f.managerIds m"
          + " where f.id = :facilityId and m = :userId")
  boolean isManagedBy(UUID facilityId, UUID userId);
}
