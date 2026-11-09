package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.application.SeriesService.CreatedSeries;
import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.domain.BookingSeries;
import dev.ethanyap.slotted.booking.domain.EffectiveStatus;
import dev.ethanyap.slotted.booking.infrastructure.BookingFilters;
import dev.ethanyap.slotted.booking.infrastructure.BookingRepository;
import dev.ethanyap.slotted.booking.infrastructure.BookingSeriesRepository;
import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.facility.application.FacilityAccess;
import dev.ethanyap.slotted.user.application.ManagedFacilities;
import dev.ethanyap.slotted.user.domain.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading bookings and series, and who may see them (PRD ST-5, MG-4). Kept apart from the services
 * that change bookings, which have enough to do.
 */
@Service
public class BookingQueries {

  private final BookingRepository bookings;
  private final BookingSeriesRepository seriesRepository;
  private final BookingLookup lookup;
  private final FacilityAccess access;
  private final ManagedFacilities managedFacilities;
  private final BookingPolicy policy;
  private final Clock clock;

  BookingQueries(
      BookingRepository bookings,
      BookingSeriesRepository seriesRepository,
      BookingLookup lookup,
      FacilityAccess access,
      ManagedFacilities managedFacilities,
      BookingPolicy policy,
      Clock clock) {
    this.bookings = bookings;
    this.seriesRepository = seriesRepository;
    this.lookup = lookup;
    this.access = access;
    this.managedFacilities = managedFacilities;
    this.policy = policy;
    this.clock = clock;
  }

  /** A booking the caller made, or one in a facility they manage. */
  @Transactional(readOnly = true)
  public BookingView get(UUID actorId, UUID bookingId) {
    var booking = bookings.findById(bookingId).orElseThrow(BookingQueries::bookingNotFound);
    var room = lookup.room(booking.getRoomId());
    var staff = access.canManage(room.getFacilityId(), actorId);
    if (!booking.getUserId().equals(actorId) && !staff) {
      throw bookingNotFound();
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
    var actor = lookup.activeUser(actorId);
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

  /** A series the caller made, or one in a facility they manage. */
  @Transactional(readOnly = true)
  public CreatedSeries series(UUID actorId, UUID seriesId) {
    var series = visibleSeries(actorId, seriesId);
    var staff = !series.getUserId().equals(actorId);
    return new CreatedSeries(series, occurrencesOf(series, clock.instant(), staff), List.of());
  }

  /** Whether this person manages the facility, so may act on anyone's bookings in it. */
  boolean canManage(UUID facilityId, UUID actorId) {
    return access.canManage(facilityId, actorId);
  }

  BookingSeries visibleSeries(UUID actorId, UUID seriesId) {
    var series =
        seriesRepository
            .findById(seriesId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No series with this ID."));
    var room = lookup.room(series.getRoomId());
    if (!series.getUserId().equals(actorId) && !access.canManage(room.getFacilityId(), actorId)) {
      throw new ApiException(ErrorCode.NOT_FOUND, "No series with this ID.");
    }
    return series;
  }

  List<BookingView> occurrencesOf(BookingSeries series, Instant now, boolean withNames) {
    return lookup.viewsOf(bookings.findBySeriesIdOrderByStartAt(series.getId()), now, withNames);
  }

  private Instant noShowCutoff(Instant now) {
    return now.minus(policy.noShowGrace());
  }

  private static ApiException bookingNotFound() {
    return new ApiException(ErrorCode.NOT_FOUND, "No booking with this ID.");
  }

  private static ApiException forbidden() {
    return new ApiException(ErrorCode.FORBIDDEN);
  }

  /** Filters for the manager list; null means no filter. */
  public record BookingQuery(
      UUID facilityId, UUID roomId, EffectiveStatus status, Instant from, Instant to) {}
}
