package dev.ethanyap.slotted.booking.infrastructure;

import dev.ethanyap.slotted.booking.domain.Blackout;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BlackoutRepository extends JpaRepository<Blackout, UUID> {

  /** Blackouts in a facility that overlap a period, earliest first. */
  @Query(
      "select b from Blackout b where b.facilityId = :facilityId"
          + " and b.startAt < :to and b.endAt > :from order by b.startAt")
  List<Blackout> findOverlapping(UUID facilityId, Instant from, Instant to);
}
