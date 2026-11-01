package dev.ethanyap.slotted.facility.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.DayOfWeek;

/** Stores weekdays as ISO numbers, 1 = Monday to 7 = Sunday, as in docs/ERD.md. */
@Converter
public class DayOfWeekConverter implements AttributeConverter<DayOfWeek, Short> {

  @Override
  public Short convertToDatabaseColumn(DayOfWeek day) {
    return day == null ? null : (short) day.getValue();
  }

  @Override
  public DayOfWeek convertToEntityAttribute(Short value) {
    return value == null ? null : DayOfWeek.of(value);
  }
}
