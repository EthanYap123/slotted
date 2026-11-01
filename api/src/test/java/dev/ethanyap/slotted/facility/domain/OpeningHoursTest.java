package dev.ethanyap.slotted.facility.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ethanyap.slotted.common.ApiException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** BR-1, BR-4 and BR-29 as they apply to opening hours. */
class OpeningHoursTest {

  @Test
  void aDayOpensOnTheHourOrHalfHourAndClosesLater() {
    var period = new OpeningPeriod(DayOfWeek.MONDAY, LocalTime.of(8, 30), LocalTime.of(22, 0));

    assertThat(period.closesAt()).isAfter(period.opensAt());
  }

  @ParameterizedTest
  @CsvSource({"09:00,09:00", "18:00,09:00", "09:15,18:00", "09:00,17:45", "09:00:30,18:00"})
  void invalidPeriodsAreRejected(LocalTime opensAt, LocalTime closesAt) {
    assertThatThrownBy(() -> new OpeningPeriod(DayOfWeek.MONDAY, opensAt, closesAt))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void theErrorNamesTheDay() {
    assertThatThrownBy(
            () -> new OpeningPeriod(DayOfWeek.FRIDAY, LocalTime.of(18, 0), LocalTime.of(9, 0)))
        .hasMessageStartingWith("Friday:");
  }

  @Test
  void aWeekListsEachDayOnceAndComesBackInOrder() {
    var facility = new Facility("Library", null, null);

    facility.replaceOpeningHours(
        List.of(
            new OpeningPeriod(DayOfWeek.SUNDAY, LocalTime.of(10, 0), LocalTime.of(16, 0)),
            new OpeningPeriod(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(22, 0))));

    assertThat(facility.getOpeningHours())
        .extracting(OpeningPeriod::dayOfWeek)
        .containsExactly(DayOfWeek.MONDAY, DayOfWeek.SUNDAY);
    assertThatThrownBy(
            () ->
                facility.replaceOpeningHours(
                    List.of(
                        new OpeningPeriod(
                            DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(12, 0)),
                        new OpeningPeriod(
                            DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(18, 0)))))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void weekdaysAreStoredAsIsoNumbers() {
    var converter = new DayOfWeekConverter();

    assertThat(converter.convertToDatabaseColumn(DayOfWeek.MONDAY)).isEqualTo((short) 1);
    assertThat(converter.convertToDatabaseColumn(DayOfWeek.SUNDAY)).isEqualTo((short) 7);
    assertThat(converter.convertToEntityAttribute((short) 3)).isEqualTo(DayOfWeek.WEDNESDAY);
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
    assertThat(converter.convertToEntityAttribute(null)).isNull();
  }

  @Test
  void blankDescriptionAndLocationAreStoredAsMissing() {
    var facility = new Facility("  Hall  ", "  ", "");

    assertThat(facility.getName()).isEqualTo("Hall");
    assertThat(facility.getDescription()).isNull();
    assertThat(facility.getLocation()).isNull();
  }
}
