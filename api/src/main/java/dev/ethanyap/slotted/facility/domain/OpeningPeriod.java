package dev.ethanyap.slotted.facility.domain;

import dev.ethanyap.slotted.common.ApiException;
import dev.ethanyap.slotted.common.ErrorCode;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Locale;

/**
 * When a facility is open on one weekday, in the facility's zone (BR-29). Times are on the hour or
 * half hour, and closing is after opening on the same day, so bookings never cross midnight.
 */
@Embeddable
public record OpeningPeriod(
    @Convert(converter = DayOfWeekConverter.class) DayOfWeek dayOfWeek,
    LocalTime opensAt,
    LocalTime closesAt) {

  public OpeningPeriod {
    if (dayOfWeek == null || opensAt == null || closesAt == null) {
      throw invalid("Each day needs dayOfWeek, opensAt and closesAt.");
    }
    if (!onHalfHour(opensAt) || !onHalfHour(closesAt)) {
      throw invalid("Opening hours must be on the hour or half hour, for example 08:00 or 21:30.");
    }
    if (!closesAt.isAfter(opensAt)) {
      throw invalid(dayName(dayOfWeek) + ": closing time must be after opening time.");
    }
  }

  private static boolean onHalfHour(LocalTime time) {
    return time.getMinute() % 30 == 0 && time.getSecond() == 0 && time.getNano() == 0;
  }

  private static String dayName(DayOfWeek day) {
    var name = day.name();
    return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
  }

  private static ApiException invalid(String detail) {
    return new ApiException(ErrorCode.VALIDATION_FAILED, detail);
  }
}
