package dev.ethanyap.slotted.booking.infrastructure;

import dev.ethanyap.slotted.booking.domain.WaitlistEntry;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Waitlist queries. "Waiting" always means stored as waiting and not started yet, because an entry
 * expires from the moment its slot starts, whether or not anything has written that down (BR-28).
 */
public interface WaitlistRepository extends JpaRepository<WaitlistEntry, UUID> {

  /** The "still waiting" condition, shared by the queries below. */
  String WAITING =
      "e.status = dev.ethanyap.slotted.booking.domain.WaitlistStatus.WAITING and e.startAt > :now";

  /** A room's queue, oldest first (BR-25). */
  @Query(
      "select e from WaitlistEntry e where e.roomId = :roomId and "
          + WAITING
          + " order by e.createdAt, e.id")
  List<WaitlistEntry> findQueue(UUID roomId, Instant now);

  @Query("select count(e) from WaitlistEntry e where e.userId = :userId and " + WAITING)
  long countWaiting(UUID userId, Instant now);

  @Query(
      "select count(e) > 0 from WaitlistEntry e where e.userId = :userId and e.roomId = :roomId"
          + " and e.startAt = :startAt and e.endAt = :endAt and "
          + WAITING)
  boolean isWaitingFor(UUID userId, UUID roomId, Instant startAt, Instant endAt, Instant now);

  /**
   * Entries ahead of this one that want some of the same time. Entries for other times in the room
   * do not compete with it, so they do not count.
   */
  @Query(
      "select count(e) from WaitlistEntry e where e.roomId = :roomId"
          + " and e.startAt < :endAt and e.endAt > :startAt and "
          + WAITING
          + " and (e.createdAt < :createdAt or (e.createdAt = :createdAt and e.id < :id))")
  long countAhead(
      UUID roomId, Instant startAt, Instant endAt, Instant createdAt, UUID id, Instant now);

  /** The latest entries of a student; at most three can be waiting, so this covers all of them. */
  List<WaitlistEntry> findTop50ByUserIdOrderByStartAtDesc(UUID userId);

  @Query("select e from WaitlistEntry e where e.userId = :userId and " + WAITING)
  List<WaitlistEntry> findWaitingByUser(UUID userId, Instant now);

  @Query("select e from WaitlistEntry e where e.roomId in :roomIds and " + WAITING)
  List<WaitlistEntry> findWaitingInRooms(Collection<UUID> roomIds, Instant now);

  @Query(
      "select e from WaitlistEntry e where e.roomId in :roomIds"
          + " and e.startAt < :to and e.endAt > :from and "
          + WAITING)
  List<WaitlistEntry> findWaitingInRoomsBetween(
      Collection<UUID> roomIds, Instant from, Instant to, Instant now);

  /** Records the expiry of waiting entries whose slot has started (BR-28). */
  @Modifying(flushAutomatically = true)
  @Query(
      "update WaitlistEntry e set e.status = dev.ethanyap.slotted.booking.domain.WaitlistStatus.EXPIRED,"
          + " e.resolvedAt = :now"
          + " where e.status = dev.ethanyap.slotted.booking.domain.WaitlistStatus.WAITING"
          + " and e.startAt <= :now")
  int expireStarted(Instant now);

  /** The room an entry is in, read without loading the entry, so it can be locked first. */
  @Query("select e.roomId from WaitlistEntry e where e.id = :id")
  Optional<UUID> findRoomIdById(UUID id);
}
