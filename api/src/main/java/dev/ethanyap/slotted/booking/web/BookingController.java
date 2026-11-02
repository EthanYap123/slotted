package dev.ethanyap.slotted.booking.web;

import dev.ethanyap.slotted.booking.application.BookingService;
import dev.ethanyap.slotted.booking.application.BookingService.BookingQuery;
import dev.ethanyap.slotted.booking.application.BookingService.NewBooking;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.domain.EffectiveStatus;
import dev.ethanyap.slotted.common.PageResponse;
import dev.ethanyap.slotted.common.Principals;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /bookings} in docs/api/openapi.yaml. */
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

  private final BookingService bookings;
  private final BookingPolicy policy;

  public BookingController(BookingService bookings, BookingPolicy policy) {
    this.bookings = bookings;
    this.policy = policy;
  }

  @PostMapping
  public ResponseEntity<BookingResponse> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BookingRequest request) {
    var view =
        bookings.create(
            Principals.userId(jwt),
            new NewBooking(
                request.roomId(),
                request.startAt().toInstant(),
                request.endAt().toInstant(),
                request.attendees(),
                request.purpose()));
    return ResponseEntity.created(URI.create("/api/v1/bookings/" + view.booking().getId()))
        .body(BookingResponse.from(view, policy));
  }

  @GetMapping("/mine")
  public PageResponse<BookingResponse> mine(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "upcoming") @Pattern(regexp = "upcoming|past") String scope,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    var upcoming = scope.equals("upcoming");
    var order = upcoming ? Sort.by("startAt").ascending() : Sort.by("startAt").descending();
    return PageResponse.of(
        bookings.mine(Principals.userId(jwt), upcoming, PageRequest.of(page, size, order)),
        v -> BookingResponse.from(v, policy));
  }

  /** Managers see their facilities; admins see everything. */
  @GetMapping
  public PageResponse<BookingResponse> list(
      @AuthenticationPrincipal Jwt jwt, @Valid @ModelAttribute ListParams params) {
    var query =
        new BookingQuery(
            params.facilityId(),
            params.roomId(),
            params.status(),
            params.from() == null ? null : params.from().toInstant(),
            params.to() == null ? null : params.to().toInstant());
    var page =
        PageRequest.of(
            params.page() == null ? 0 : params.page(),
            params.size() == null ? 20 : params.size(),
            Sort.by("startAt"));
    return PageResponse.of(
        bookings.list(Principals.userId(jwt), query, page), v -> BookingResponse.from(v, policy));
  }

  /** Query parameters of {@code GET /bookings}; all optional. */
  public record ListParams(
      UUID facilityId,
      UUID roomId,
      EffectiveStatus status,
      OffsetDateTime from,
      OffsetDateTime to,
      @Min(0) Integer page,
      @Min(1) @Max(100) Integer size) {}

  @GetMapping("/{bookingId}")
  public BookingResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID bookingId) {
    return BookingResponse.from(bookings.get(Principals.userId(jwt), bookingId), policy);
  }

  @PostMapping("/{bookingId}/cancel")
  public BookingResponse cancel(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID bookingId,
      @Valid @RequestBody(required = false) CancelRequest request) {
    var reason = request == null ? null : request.reason();
    return BookingResponse.from(bookings.cancel(Principals.userId(jwt), bookingId, reason), policy);
  }

  /** Schema {@code BookingInput}. Times need an offset, for example {@code +08:00}. */
  public record BookingRequest(
      @NotNull UUID roomId,
      @NotNull OffsetDateTime startAt,
      @NotNull OffsetDateTime endAt,
      @NotNull @Min(1) @Max(500) Integer attendees,
      @Size(max = 200) String purpose) {}

  /** Schema {@code CancelRequest}. */
  public record CancelRequest(@Size(max = 500) String reason) {}
}
