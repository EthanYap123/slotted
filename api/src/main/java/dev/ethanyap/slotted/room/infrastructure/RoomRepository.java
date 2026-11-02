package dev.ethanyap.slotted.room.infrastructure;

import dev.ethanyap.slotted.room.domain.Room;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, UUID> {

  List<Room> findByFacilityIdOrderByName(UUID facilityId);

  boolean existsByFacilityIdAndNameIgnoreCase(UUID facilityId, String name);

  boolean existsByFacilityIdAndNameIgnoreCaseAndIdNot(UUID facilityId, String name, UUID id);

  List<Room> findByFacilityIdIn(Collection<UUID> facilityIds);
}
