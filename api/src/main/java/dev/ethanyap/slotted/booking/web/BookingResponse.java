package dev.ethanyap.slotted.booking.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.ethanyap.slotted.booking.application.BookingView;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.domain.BookingSource;
import dev.ethanyap.slotted.booking.domain.EffectiveStatus;
import dev.ethanyap.slotted.common.ApiTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Schema {@code Booking}. The booker's name is only included for managers and admins. */
public record BookingResponse(
    UUID id,
    UUID roomId,
    String roomName,
    OffsetDateTime startAt,
    OffsetDateTime endAt,
    EffectiveStatus status,
    @JsonInclude(JsonInclude.Include.NON_NULL) String userName,
    UUID facilityId,
    String facilityName,
    UUID userId,
    UUID seriesId,
    int attendees,
    String purpose,
    BookingSource source,
    OffsetDateTime checkInOpensAt,
    OffsetDateTime checkInClosesAt,
    OffsetDateTime checkedInAt,
    OffsetDateTime releasedAt,
    OffsetDateTime cancelledAt,
    String cancelReason,
    boolean lateCancellation,
    OffsetDateTime createdAt) {

  static BookingResponse from(BookingView view, BookingPolicy policy) {
    var b = view.booking();
    return new BookingResponse(
        b.getId(),
        b.getRoomId(),
        view.room().getName(),
        ApiTime.toApi(b.getStartAt()),
        ApiTime.toApi(b.getEndAt()),
        view.status(),
        view.bookerName(),
        view.facility().getId(),
        view.facility().getName(),
        b.getUserId(),
        b.getSeriesId(),
        b.getAttendees(),
        b.getPurpose(),
        b.getSource(),
        ApiTime.toApi(b.checkInOpensAt(policy)),
        ApiTime.toApi(b.noShowAt(policy)),
        ApiTime.toApi(b.getCheckedInAt()),
        ApiTime.toApi(b.getReleasedAt()),
        ApiTime.toApi(b.getCancelledAt()),
        b.getCancelReason(),
        b.isLateCancellation(),
        ApiTime.toApi(b.getCreatedAt()));
  }
}
