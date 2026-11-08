package dev.ethanyap.slotted.room.infrastructure;

import dev.ethanyap.slotted.room.domain.Room;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RoomRepository extends JpaRepository<Room, UUID> {

  List<Room> findByFacilityIdOrderByName(UUID facilityId);

  boolean existsByFacilityIdAndNameIgnoreCase(UUID facilityId, String name);

  boolean existsByFacilityIdAndNameIgnoreCaseAndIdNot(UUID facilityId, String name, UUID id);

  List<Room> findByFacilityIdIn(Collection<UUID> facilityIds);

  /**
   * Loads and locks the room until the transaction ends, so bookings for one room are made one at a
   * time (ADR-0006).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Room r where r.id = :id")
  Optional<Room> lockById(UUID id);

  Optional<Room> findByCheckInToken(String checkInToken);

  /** Every room's ID, in a fixed order. */
  @Query("select r.id from Room r order by r.id")
  List<UUID> findAllIds();

  /** Locks a facility's rooms in ID order, so two callers cannot lock them in opposite orders. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Room r where r.facilityId = :facilityId order by r.id")
  List<Room> lockByFacilityId(UUID facilityId);
}
