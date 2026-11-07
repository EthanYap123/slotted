package dev.ethanyap.slotted.booking.web;

import dev.ethanyap.slotted.booking.application.BookingAttempts;
import dev.ethanyap.slotted.booking.application.BookingCancellation;
import dev.ethanyap.slotted.booking.application.SeriesService;
import dev.ethanyap.slotted.booking.application.SeriesService.CreatedSeries;
import dev.ethanyap.slotted.booking.application.SeriesService.NewSeries;
import dev.ethanyap.slotted.booking.application.SeriesService.Occurrence;
import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import dev.ethanyap.slotted.booking.domain.BookingSeries.Frequency;
import dev.ethanyap.slotted.common.ApiTime;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.common.Principals;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /booking-series} in docs/api/openapi.yaml. */
@RestController
@RequestMapping("/api/v1/booking-series")
public class SeriesController {

  private final SeriesService series;
  private final BookingAttempts attempts;
  private final BookingCancellation cancellation;
  private final BookingPolicy policy;

  public SeriesController(
      SeriesService series,
      BookingAttempts attempts,
      BookingCancellation cancellation,
      BookingPolicy policy) {
    this.series = series;
    this.attempts = attempts;
    this.cancellation = cancellation;
    this.policy = policy;
  }

  @PostMapping("/preview")
  public SeriesPreview preview(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SeriesInput input) {
    var occurrences = series.preview(Principals.userId(jwt), input.toNewSeries());
    return new SeriesPreview(
        occurrences.stream().map(OccurrenceCheck::from).toList(),
        occurrences.stream().filter(Occurrence::bookable).count());
  }

  @PostMapping
  public ResponseEntity<SeriesResponse> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SeriesInput input) {
    var created = attempts.createSeries(Principals.userId(jwt), input.toNewSeries());
    return ResponseEntity.created(URI.create("/api/v1/booking-series/" + created.series().getId()))
        .body(SeriesResponse.from(created, policy));
  }

  @GetMapping("/{seriesId}")
  public SeriesResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID seriesId) {
    return SeriesResponse.from(series.get(Principals.userId(jwt), seriesId), policy);
  }

  @PostMapping("/{seriesId}/cancel")
  public SeriesResponse cancel(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID seriesId,
      @Valid @RequestBody CancelSeriesRequest request) {
    return SeriesResponse.from(
        cancellation.cancelSeriesFrom(
            Principals.userId(jwt), seriesId, request.fromDate(), request.reason()),
        policy);
  }

  /** Schema {@code SeriesInput}. */
  public record SeriesInput(
      @NotNull UUID roomId,
      @NotNull OffsetDateTime firstStartAt,
      @NotNull OffsetDateTime firstEndAt,
      @NotNull Frequency frequency,
      @NotNull @Min(2) @Max(52) Integer occurrences,
      @NotNull @Min(1) @Max(500) Integer attendees,
      @Size(max = 200) String purpose) {

    NewSeries toNewSeries() {
      return new NewSeries(
          roomId,
          firstStartAt.toInstant(),
          firstEndAt.toInstant(),
          frequency,
          occurrences,
          attendees,
          purpose);
    }
  }

  /** Body of {@code POST /booking-series/{id}/cancel}. */
  public record CancelSeriesRequest(@NotNull LocalDate fromDate, @Size(max = 500) String reason) {}

  /** Schema {@code OccurrenceCheck}. */
  public record OccurrenceCheck(
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      boolean bookable,
      ErrorCode reason,
      String detail) {

    static OccurrenceCheck from(Occurrence occurrence) {
      return new OccurrenceCheck(
          ApiTime.toApi(occurrence.startAt()),
          ApiTime.toApi(occurrence.endAt()),
          occurrence.bookable(),
          occurrence.reason(),
          occurrence.detail());
    }
  }

  /** Schema {@code SeriesPreview}. */
  public record SeriesPreview(List<OccurrenceCheck> occurrences, long bookableCount) {}

  /** Schema {@code Series}. */
  public record SeriesResponse(
      UUID id,
      UUID roomId,
      Frequency frequency,
      int occurrencesRequested,
      List<BookingResponse> bookings,
      List<OccurrenceCheck> skipped) {

    static SeriesResponse from(CreatedSeries created, BookingPolicy policy) {
      var series = created.series();
      return new SeriesResponse(
          series.getId(),
          series.getRoomId(),
          series.getFrequency(),
          series.getOccurrencesRequested(),
          created.bookings().stream().map(v -> BookingResponse.from(v, policy)).toList(),
          created.skipped().stream().map(OccurrenceCheck::from).toList());
    }
  }
}
