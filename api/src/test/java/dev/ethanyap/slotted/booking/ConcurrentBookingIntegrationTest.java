package dev.ethanyap.slotted.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ethanyap.slotted.Api;
import dev.ethanyap.slotted.Api.TestUser;
import dev.ethanyap.slotted.IntegrationTest;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Issue #39 and ADR-0001: requests that race for the same slot are released at the same moment by a
 * latch, and exactly one may win. Runs against real Postgres; the application's own overlap check
 * cannot stop these races (every request passes it before any insert commits), so the result
 * depends on the exclusion constraint and the per-student row lock.
 */
@IntegrationTest
class ConcurrentBookingIntegrationTest {

  private static final ZoneId SGT = ZoneId.of("Asia/Singapore");
  private static final int RACERS = 16;

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  Api api;
  String adminToken;
  String roomId;
  LocalDate tomorrow;

  @BeforeEach
  void setUp() {
    api = new Api(http);
    adminToken = api.adminToken();
    var facility = api.createFacility(adminToken);
    var week = new ArrayList<Map<String, String>>();
    for (var day : DayOfWeek.values()) {
      week.add(Map.of("dayOfWeek", day.name(), "opensAt", "08:00", "closesAt", "22:00"));
    }
    api.put("/api/v1/facilities/" + facility + "/opening-hours", week, adminToken);
    roomId =
        api.post(
                "/api/v1/facilities/" + facility + "/rooms",
                Map.of("name", "Popular Room", "type", "STUDY_ROOM", "capacity", 10),
                adminToken)
            .getBody()
            .path("id")
            .asText();
    tomorrow = LocalDate.now(SGT).plusDays(1);
  }

  @RepeatedTest(3)
  void manyStudentsBookTheSameSlotAtOnceAndExactlyOneWins() throws Exception {
    var students = new ArrayList<TestUser>();
    for (var i = 0; i < RACERS; i++) {
      students.add(api.newStudent());
    }

    var responses =
        race(
            students.stream()
                .<Callable<ResponseEntity<JsonNode>>>map(
                    s -> () -> book(s.token(), tomorrow, "14:00", "15:00"))
                .toList());

    assertThat(responses)
        .as(summary(responses))
        .filteredOn(r -> r.getStatusCode().value() == 201)
        .hasSize(1);
    assertThat(responses)
        .as(summary(responses))
        .filteredOn(r -> r.getStatusCode().value() == 409)
        .hasSize(RACERS - 1)
        .allSatisfy(r -> assertThat(r.getBody().path("code").asText()).isEqualTo("SLOT_TAKEN"));
    assertThat(activeBookingsInRoom()).isEqualTo(1);
  }

  @Test
  void racingPartlyOverlappingBookingsNeverOverlapInTheDatabase() throws Exception {
    var starts = List.of("10:00", "10:30", "11:00", "11:30", "12:00", "09:30", "10:00", "11:00");
    var calls = new ArrayList<Callable<ResponseEntity<JsonNode>>>();
    for (var start : starts) {
      var student = api.newStudent();
      var end = LocalTime.parse(start).plusHours(1).toString();
      calls.add(() -> book(student.token(), tomorrow, start, end));
    }

    var responses = race(calls);

    assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(201, 409));
    var overlaps =
        jdbc.queryForObject(
            "SELECT count(*) FROM booking a JOIN booking b ON a.room_id = b.room_id AND a.id < b.id"
                + " WHERE a.room_id = ?::uuid AND a.slot && b.slot"
                + " AND a.status IN ('CONFIRMED', 'CHECKED_IN')"
                + " AND b.status IN ('CONFIRMED', 'CHECKED_IN')",
            Integer.class,
            roomId);
    assertThat(overlaps).isZero();
    assertThat(activeBookingsInRoom()).isPositive();
  }

  /**
   * One student fires five bookings for different days at once. The quota allows three; the row
   * lock on the student makes the requests take turns, so the count each one sees is current.
   */
  @RepeatedTest(3)
  void oneStudentsParallelRequestsCannotBeatTheQuota() throws Exception {
    var student = api.newStudent();
    var calls = new ArrayList<Callable<ResponseEntity<JsonNode>>>();
    for (var day = 1; day <= 5; day++) {
      var date = tomorrow.plusDays(day);
      calls.add(() -> book(student.token(), date, "09:00", "10:00"));
    }

    var responses = race(calls);

    assertThat(responses).filteredOn(r -> r.getStatusCode().value() == 201).hasSize(3);
    assertThat(responses)
        .filteredOn(r -> r.getStatusCode().value() == 422)
        .hasSize(2)
        .allSatisfy(
            r ->
                assertThat(r.getBody().path("code").asText()).isEqualTo("QUOTA_UPCOMING_EXCEEDED"));
  }

  /**
   * A booking and a blackout race for the same time. Whichever gets the room lock first, no active
   * booking may be left inside the blackout: it was either refused or cancelled by the blackout.
   */
  @RepeatedTest(5)
  void aBookingRacingABlackoutIsNeverLeftInsideIt() throws Exception {
    var facility =
        api.get("/api/v1/rooms/" + roomId, adminToken).getBody().path("facilityId").asText();
    var student = api.newStudent();
    List<Callable<ResponseEntity<JsonNode>>> calls =
        List.of(
            () -> book(student.token(), tomorrow, "16:00", "17:00"),
            () ->
                api.post(
                    "/api/v1/facilities/" + facility + "/blackouts",
                    Map.of(
                        "startAt", sgt(tomorrow, "16:00"),
                        "endAt", sgt(tomorrow, "17:00"),
                        "reason", "Race"),
                    adminToken));

    var responses = race(calls);

    assertThat(responses.get(0).getStatusCode().value()).isIn(201, 422);
    assertThat(responses.get(1).getStatusCode().value()).isEqualTo(201);
    assertThat(activeBookingsInRoom()).isZero();
  }

  /** The constraint on its own, without the application: SQL that tries to double book fails. */
  @Test
  void theDatabaseRefusesOverlappingActiveBookingsWhoeverWritesThem() {
    var student = api.newStudent();
    var rows = new BookingRows(jdbc);
    var start = tomorrow.atTime(10, 0).atZone(SGT).toInstant();
    var hour = Duration.ofHours(1);
    rows.confirmed(roomId, student.id(), start, start.plus(hour));

    assertThatThrownBy(
            () ->
                rows.confirmed(
                    roomId,
                    student.id(),
                    start.plus(Duration.ofMinutes(30)),
                    start.plus(Duration.ofMinutes(90))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("booking_no_overlap");
    // Back-to-back is fine, and cancelled or no-show rows do not block.
    rows.confirmed(roomId, student.id(), start.plus(hour), start.plus(hour).plus(hour));
    rows.cancelled(roomId, student.id(), start, start.plus(hour));
    rows.recordedNoShow(roomId, student.id(), start, start.plus(hour));
    assertThat(activeBookingsInRoom()).isEqualTo(2);
  }

  /** Starts every call at the same moment and waits for all of them. */
  private static <T> List<T> race(List<Callable<T>> calls) throws Exception {
    var executor = Executors.newFixedThreadPool(calls.size());
    try {
      var ready = new CountDownLatch(calls.size());
      var go = new CountDownLatch(1);
      var futures =
          calls.stream()
              .map(
                  call ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            go.await();
                            return call.call();
                          }))
              .toList();
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      var results = new ArrayList<T>();
      for (var future : futures) {
        results.add(future.get(60, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      executor.shutdownNow();
    }
  }

  private ResponseEntity<JsonNode> book(String token, LocalDate date, String start, String end) {
    return api.post(
        "/api/v1/bookings",
        Map.of(
            "roomId", roomId, "startAt", sgt(date, start), "endAt", sgt(date, end), "attendees", 1),
        token);
  }

  /** Status and error code of every response, for failure messages. */
  private static String summary(List<ResponseEntity<JsonNode>> responses) {
    return responses.stream()
        .map(r -> r.getStatusCode().value() + " " + r.getBody().path("code").asText())
        .toList()
        .toString();
  }

  private int activeBookingsInRoom() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM booking WHERE room_id = ?::uuid"
            + " AND status IN ('CONFIRMED', 'CHECKED_IN')",
        Integer.class,
        roomId);
  }

  private static String sgt(LocalDate date, String time) {
    return date.atTime(LocalTime.parse(time)).atZone(SGT).toOffsetDateTime().toString();
  }
}
