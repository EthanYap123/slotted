package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.domain.BookingRules;
import dev.ethanyap.slotted.booking.domain.BookingRules.BookerFacts;
import dev.ethanyap.slotted.booking.domain.BookingRules.RoomFacts;
import dev.ethanyap.slotted.booking.domain.EffectiveStatus;
import dev.ethanyap.slotted.booking.domain.Suspension;
import dev.ethanyap.slotted.booking.infrastructure.BookingFilters;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.facility.application.FacilityAccess;
import dev.ethanyap.slotted.facility.domain.Facility;
import dev.ethanyap.slotted.facility.domain.OpeningPeriod;
import dev.ethanyap.slotted.user.application.ManagedFacilities;
import dev.ethanyap.slotted.user.domain.Role;
import dev.ethanyap.slotted.user.domain.User;
import dev.ethanyap.slotted.user.infrastructure.UserRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Making, cancelling and listing bookings (PRD ST-3 to ST-5, MG-4, BR-1 to BR-14). */
@Service
public class BookingService {

  /** SQLSTATE Postgres uses when an exclusion constraint rejects a row. */
  private static final String EXCLUSION_VIOLATION = "23P01";

  private final BookingRepository bookings;
  private final BookingLookup lookup;
  private final UserRepository users;
  private final FacilityAccess access;
  private final ManagedFacilities managedFacilities;
  private final BookingPolicy policy;
  private final Clock clock;

  BookingService(
      BookingRepository bookings,
      BookingLookup lookup,
      UserRepository users,
      FacilityAccess access,
      ManagedFacilities managedFacilities,
      BookingPolicy policy,
      Clock clock) {
    this.bookings = bookings;
    this.lookup = lookup;
    this.users = users;
    this.access = access;
    this.managedFacilities = managedFacilities;
    this.policy = policy;
    this.clock = clock;
  }

  /**
   * Books a slot. In one transaction: lock the booker's row (quotas), then the room (bookings for
   * one room take turns, ADR-0006), settle overdue no-shows in the room (ADR-0004), check every
   * rule, then insert. The exclusion constraint still guarantees no overlap whatever writes the row
   * (ADR-0001); a violation becomes {@code SLOT_TAKEN}. Locks are always taken in this order, so
   * booking transactions cannot deadlock on each other.
   */
  @Transactional
  public BookingView create(UUID userId, NewBooking request) {
    var user =
        users
            .lockById(userId)
            .filter(User::isActive)
            .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    var room = lookup.lockRoom(request.roomId());
    var facility = lookup.facilityOf(room);
    var now = clock.instant();
    var cutoff = noShowCutoff(now);
    bookings.settleNoShows(room.getId(), cutoff, policy.noShowGrace().toSeconds());

    var zone = ZoneId.of(facility.getTimeZone());
    BookingRules.check(
        new BookingRules.Request(request.startAt(), request.endAt(), request.attendees()),
        new RoomFacts(
            room.isActive(),
            room.getCapacity(),
            room.getMaxBookingMinutes(),
            zone,
            openingHoursOn(facility, request.startAt(), zone)),
        bookerFacts(user, request, zone, now, cutoff),
        now,
        policy);

    if (bookings.roomHasBookingBetween(room.getId(), request.startAt(), request.endAt(), cutoff)) {
      throw slotTaken();
    }
    var booking =
        new Booking(
            room.getId(),
            user.getId(),
            request.startAt(),
            request.endAt(),
            request.attendees(),
            request.purpose());
    try {
      booking = bookings.saveAndFlush(booking);
    } catch (DataIntegrityViolationException e) {
      if (isExclusionViolation(e)) {
        throw slotTaken();
      }
      throw e;
    }
    return lookup.view(booking, room, facility, now, null);
  }

  /** The owner cancels before the start; managers and admins cancel any active booking. */
  @Transactional
  public BookingView cancel(UUID actorId, UUID bookingId, String reason) {
    var booking = bookings.findById(bookingId).orElseThrow(BookingService::notFound);
    var room = lookup.room(booking.getRoomId());
    var now = clock.instant();
    if (booking.getUserId().equals(actorId)) {
      booking.cancelByOwner(now, policy, reason);
    } else if (access.canManage(room.getFacilityId(), actorId)) {
      booking.cancelByStaff(actorId, now, policy, reason);
    } else {
      throw notFound();
    }
    return lookup.view(booking, room, lookup.facilityOf(room), now, null);
  }

  /** A booking the caller made, or one in a facility they manage. */
  @Transactional(readOnly = true)
  public BookingView get(UUID actorId, UUID bookingId) {
    var booking = bookings.findById(bookingId).orElseThrow(BookingService::notFound);
    var room = lookup.room(booking.getRoomId());
    var staff = access.canManage(room.getFacilityId(), actorId);
    if (!booking.getUserId().equals(actorId) && !staff) {
      throw notFound();
    }
    var bookerName = staff ? lookup.nameOf(booking.getUserId()) : null;
    return lookup.view(booking, room, lookup.facilityOf(room), clock.instant(), bookerName);
  }

  @Transactional(readOnly = true)
  public Page<BookingView> mine(UUID userId, boolean upcoming, Pageable pageable) {
    var now = clock.instant();
    var cutoff = noShowCutoff(now);
    var page =
        upcoming
            ? bookings.findUpcoming(userId, now, cutoff, pageable)
            : bookings.findPast(userId, now, cutoff, pageable);
    return lookup.views(page, now, false);
  }

  /**
   * Bookings in the facilities the caller manages (all facilities for admins), filtered and with
   * the booker's name.
   */
  @Transactional(readOnly = true)
  public Page<BookingView> list(UUID actorId, BookingQuery query, Pageable pageable) {
    var actor =
        users.findById(actorId).filter(User::isActive).orElseThrow(BookingService::forbidden);
    Collection<UUID> facilityIds;
    if (query.facilityId() != null) {
      if (!access.canManage(query.facilityId(), actorId)) {
        throw forbidden();
      }
      facilityIds = List.of(query.facilityId());
    } else if (actor.getRole() == Role.ADMIN) {
      facilityIds = null;
    } else if (actor.getRole() == Role.MANAGER) {
      facilityIds = managedFacilities.facilityIdsManagedBy(actorId);
    } else {
      throw forbidden();
    }

    Collection<UUID> roomIds = null;
    if (facilityIds != null) {
      roomIds = lookup.roomIdsIn(facilityIds);
    }
    if (query.roomId() != null) {
      if (roomIds != null && !roomIds.contains(query.roomId())) {
        throw forbidden();
      }
      roomIds = List.of(query.roomId());
    }

    if (roomIds != null && roomIds.isEmpty()) {
      return Page.empty(pageable);
    }
    var now = clock.instant();
    Specification<Booking> filter =
        Specification.allOf(
            BookingFilters.inRooms(roomIds),
            BookingFilters.overlapping(query.from(), query.to()),
            BookingFilters.effectiveStatus(query.status(), now, noShowCutoff(now)));
    return lookup.views(bookings.findAll(filter, pageable), now, true);
  }

  private BookerFacts bookerFacts(
      User user, NewBooking request, ZoneId zone, Instant now, Instant cutoff) {
    if (user.getRole() != Role.STUDENT) {
      return new BookerFacts(false, 0, 0, false, Optional.empty());
    }
    var day = request.startAt().atZone(zone).toLocalDate();
    var dayStart = day.atStartOfDay(zone).toInstant();
    var dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
    var minutesThatDay =
        bookings.findCountingStartingBetween(user.getId(), dayStart, dayEnd, cutoff).stream()
            .mapToLong(b -> Duration.between(b.getStartAt(), b.getEndAt()).toMinutes())
            .sum();
    return new BookerFacts(
        true,
        bookings.countUpcoming(user.getId(), now),
        minutesThatDay,
        bookings.userHasBookingBetween(user.getId(), request.startAt(), request.endAt(), cutoff),
        suspendedUntil(List.of(user), now).values().stream().findFirst());
  }

  /** Ends of suspensions in force for these users (BR-18). */
  Map<UUID, Instant> suspendedUntil(Collection<User> forUsers, Instant now) {
    if (forUsers.isEmpty()) {
      return Map.of();
    }
    var since = now.minus(policy.noShowPeriod()).minus(policy.noShowGrace());
    var byUser =
        bookings
            .findNoShows(forUsers.stream().map(User::getId).toList(), since, noShowCutoff(now))
            .stream()
            .collect(
                Collectors.groupingBy(
                    BookingRepository.NoShow::getUserId,
                    Collectors.mapping(
                        n -> n.getStartAt().plus(policy.noShowGrace()), Collectors.toList())));
    return forUsers.stream()
        .flatMap(
            u ->
                Suspension.until(
                    byUser.getOrDefault(u.getId(), List.of()),
                    u.getSuspensionClearedAt(),
                    now,
                    policy)
                    .map(until -> Map.entry(u.getId(), until))
                    .stream())
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private Optional<OpeningPeriod> openingHoursOn(Facility facility, Instant at, ZoneId zone) {
    var day = at.atZone(zone).getDayOfWeek();
    return facility.getOpeningHours().stream().filter(p -> p.dayOfWeek() == day).findFirst();
  }

  private Instant noShowCutoff(Instant now) {
    return now.minus(policy.noShowGrace());
  }

  private static boolean isExclusionViolation(Throwable e) {
    for (var cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  private static ApiException slotTaken() {
    return new ApiException(
        ErrorCode.SLOT_TAKEN, "Someone else has booked this room for some or all of that time.");
  }

  private static ApiException notFound() {
    return new ApiException(ErrorCode.NOT_FOUND, "No booking with this ID.");
  }

  private static ApiException forbidden() {
    return new ApiException(ErrorCode.FORBIDDEN);
  }

  /** What the caller asked to book. */
  public record NewBooking(
      UUID roomId, Instant startAt, Instant endAt, int attendees, String purpose) {}

  /** Filters for the manager list; null means no filter. */
  public record BookingQuery(
      UUID facilityId, UUID roomId, EffectiveStatus status, Instant from, Instant to) {}
}
