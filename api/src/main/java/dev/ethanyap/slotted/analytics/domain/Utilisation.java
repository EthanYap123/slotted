package dev.ethanyap.slotted.analytics.domain;

import dev.ethanyap.slotted.facility.domain.OpeningPeriod;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Works out how rooms were used over a range of dates (PRD AD-4). Plain Java, so the numbers can be
 * checked without a database.
 *
 * <p>Time is looked at in 30-minute cells, the size of a booking step (BR-1). A cell counts as open
 * when the facility's opening hours cover it, less any blackout. Booked time is time held by a
 * booking that still counts: checked in, or confirmed and not yet a no-show (ADR-0004). So a
 * no-show frees its room and does not count as use, and a booking ended early counts only until it
 * ended. Utilisation is booked time divided by open time.
 */
public final class Utilisation {

  private static final Duration CELL = Duration.ofMinutes(30);
  private static final int DAYS_IN_WEEK = 7;
  private static final int HOURS_IN_DAY = 24;
  private static final double SECONDS_PER_HOUR = 3600.0;

  private Utilisation() {}

  /**
   * The report for {@code rooms} from {@code fromDate} to {@code toDate}, both inclusive, in each
   * room's zone. Attendance counts look at bookings that start in the range.
   */
  public static Report calculate(
      LocalDate fromDate, LocalDate toDate, List<RoomInput> rooms, List<BookingFact> bookings) {
    var heat = new Heat();
    var usages = new ArrayList<RoomUsage>();
    for (var room : rooms) {
      var mine = bookings.stream().filter(b -> b.roomId().equals(room.roomId())).toList();
      var held =
          merge(
              mine.stream()
                  .filter(b -> b.outcome().holdsRoom())
                  .map(b -> new Interval(b.startAt(), b.endAt()))
                  .toList());
      var blackouts = merge(room.blackouts());
      var time = new long[2];
      for (var date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
        addDay(room, date, new Covered(held, blackouts), time, heat);
      }
      var counted = startingIn(mine, fromDate, toDate, room.zone());
      usages.add(usage(room, time[0], time[1], counted));
    }
    return new Report(usages, heat.cells());
  }

  /** Adds one day's open and booked seconds for a room to {@code time} and to the heat map. */
  private static void addDay(
      RoomInput room, LocalDate date, Covered covered, long[] time, Heat heat) {
    var day = date.getDayOfWeek();
    var hours = room.openingHours().stream().filter(p -> p.dayOfWeek() == day).findFirst();
    if (hours.isEmpty()) {
      return;
    }
    var closes = date.atTime(hours.get().closesAt());
    for (var local = date.atTime(hours.get().opensAt());
        local.isBefore(closes);
        local = local.plus(CELL)) {
      var start = local.atZone(room.zone()).toInstant();
      var openParts = subtract(new Interval(start, start.plus(CELL)), covered.blackouts());
      var open = openParts.stream().mapToLong(Interval::seconds).sum();
      var booked = openParts.stream().mapToLong(part -> overlap(part, covered.held())).sum();
      time[0] += open;
      time[1] += booked;
      heat.add(day.getValue(), local.getHour(), open, booked);
    }
  }

  private static List<BookingFact> startingIn(
      List<BookingFact> bookings, LocalDate fromDate, LocalDate toDate, ZoneId zone) {
    var from = fromDate.atStartOfDay(zone).toInstant();
    var to = toDate.plusDays(1).atStartOfDay(zone).toInstant();
    return bookings.stream()
        .filter(b -> !b.startAt().isBefore(from) && b.startAt().isBefore(to))
        .toList();
  }

  private static RoomUsage usage(
      RoomInput room, long open, long booked, List<BookingFact> counted) {
    var checkedIn = count(counted, Outcome.CHECKED_IN);
    var noShows = count(counted, Outcome.NO_SHOW);
    var cancelled = count(counted, Outcome.CANCELLED);
    var late =
        (int)
            counted.stream()
                .filter(b -> b.outcome() == Outcome.CANCELLED && b.lateCancellation())
                .count();
    var started = checkedIn + noShows;
    return new RoomUsage(
        room.roomId(),
        room.roomName(),
        room.facilityId(),
        room.facilityName(),
        hours(open),
        hours(booked),
        ratio(booked, open),
        counted.size() - cancelled,
        ratio(checkedIn, started),
        ratio(noShows, started),
        cancelled,
        late);
  }

  private static int count(List<BookingFact> bookings, Outcome outcome) {
    return (int) bookings.stream().filter(b -> b.outcome() == outcome).count();
  }

  /** {@code part / whole}, from 0 to 1, to three decimal places; 0 when there is no whole. */
  static double ratio(long part, long whole) {
    if (whole <= 0) {
      return 0;
    }
    return Math.round(Math.min(1.0, (double) part / whole) * 1000) / 1000.0;
  }

  private static double hours(long seconds) {
    return Math.round(seconds / SECONDS_PER_HOUR * 100) / 100.0;
  }

  /** Joins overlapping or touching intervals, so no time is counted twice. */
  static List<Interval> merge(Collection<Interval> intervals) {
    var sorted = intervals.stream().sorted(Comparator.comparing(Interval::start)).toList();
    var merged = new ArrayList<Interval>();
    for (var next : sorted) {
      if (!merged.isEmpty() && !next.start().isAfter(merged.getLast().end())) {
        var last = merged.removeLast();
        var end = next.end().isAfter(last.end()) ? next.end() : last.end();
        merged.add(new Interval(last.start(), end));
      } else {
        merged.add(next);
      }
    }
    return merged;
  }

  /** The parts of {@code cell} that no interval in {@code merged} covers. */
  static List<Interval> subtract(Interval cell, List<Interval> merged) {
    var parts = new ArrayList<Interval>();
    var cursor = cell.start();
    for (var cut : merged) {
      if (!cut.end().isAfter(cursor) || !cut.start().isBefore(cell.end())) {
        continue;
      }
      if (cut.start().isAfter(cursor)) {
        parts.add(new Interval(cursor, cut.start()));
      }
      cursor = cut.end();
    }
    if (cursor.isBefore(cell.end())) {
      parts.add(new Interval(cursor, cell.end()));
    }
    return parts;
  }

  /** Seconds of {@code part} covered by {@code merged}. */
  static long overlap(Interval part, List<Interval> merged) {
    long total = 0;
    for (var other : merged) {
      var start = other.start().isAfter(part.start()) ? other.start() : part.start();
      var end = other.end().isBefore(part.end()) ? other.end() : part.end();
      if (start.isBefore(end)) {
        total += Duration.between(start, end).toSeconds();
      }
    }
    return total;
  }

  /** Open and booked seconds for each weekday and hour, all rooms together. */
  private static final class Heat {

    private final long[][] open = new long[DAYS_IN_WEEK][HOURS_IN_DAY];
    private final long[][] booked = new long[DAYS_IN_WEEK][HOURS_IN_DAY];

    void add(int dayOfWeek, int hour, long openSeconds, long bookedSeconds) {
      open[dayOfWeek - 1][hour] += openSeconds;
      booked[dayOfWeek - 1][hour] += bookedSeconds;
    }

    /** Only hours that were open at some point. */
    List<HeatCell> cells() {
      var cells = new ArrayList<HeatCell>();
      for (var day = 0; day < DAYS_IN_WEEK; day++) {
        for (var hour = 0; hour < HOURS_IN_DAY; hour++) {
          if (open[day][hour] > 0) {
            cells.add(new HeatCell(day + 1, hour, ratio(booked[day][hour], open[day][hour])));
          }
        }
      }
      return cells;
    }
  }

  /** A room's merged booked time and blackouts. */
  private record Covered(List<Interval> held, List<Interval> blackouts) {}

  /** A half-open period {@code [start, end)}. */
  public record Interval(Instant start, Instant end) {

    long seconds() {
      return Duration.between(start, end).toSeconds();
    }
  }

  /** What became of a booking, for attendance. */
  public enum Outcome {
    /** Confirmed, and not a no-show yet. */
    UPCOMING,
    CHECKED_IN,
    NO_SHOW,
    CANCELLED;

    boolean holdsRoom() {
      return this == UPCOMING || this == CHECKED_IN;
    }
  }

  /** A room with the hours it could be used. */
  public record RoomInput(
      UUID roomId,
      String roomName,
      UUID facilityId,
      String facilityName,
      ZoneId zone,
      List<OpeningPeriod> openingHours,
      List<Interval> blackouts) {}

  /** A booking, reduced to what the report needs. */
  public record BookingFact(
      UUID roomId, Instant startAt, Instant endAt, Outcome outcome, boolean lateCancellation) {}

  /**
   * One room's numbers.
   *
   * @param bookings bookings that started in the range and were not cancelled
   * @param checkInRate share of the bookings that have started which were checked in
   */
  public record RoomUsage(
      UUID roomId,
      String roomName,
      UUID facilityId,
      String facilityName,
      double openHours,
      double bookedHours,
      double utilisation,
      int bookings,
      double checkInRate,
      double noShowRate,
      int cancellations,
      int lateCancellations) {}

  /** Utilisation of every room together in one hour of one weekday (1 = Monday). */
  public record HeatCell(int dayOfWeek, int hour, double utilisation) {}

  /** The whole report. */
  public record Report(List<RoomUsage> rooms, List<HeatCell> heatmap) {}
}
