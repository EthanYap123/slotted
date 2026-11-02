package dev.ethanyap.slotted.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ethanyap.slotted.Api;
import dev.ethanyap.slotted.Api.TestUser;
import dev.ethanyap.slotted.IntegrationTest;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** PRD ST-2 to ST-5, MG-4 and BR-1 to BR-18, end to end against Postgres. */
@IntegrationTest
class BookingsIntegrationTest {

  private static final ZoneId SGT = ZoneId.of("Asia/Singapore");

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  Api api;
  BookingRows rows;
  String adminToken;
  String facilityId;
  String roomA;
  String roomB;
  TestUser manager;
  LocalDate tomorrow;

  @BeforeEach
  void setUp() {
    api = new Api(http);
    rows = new BookingRows(jdbc);
    adminToken = api.adminToken();
    facilityId = api.createFacility(adminToken);
    openEveryDay(facilityId, "08:00", "22:00");
    manager = api.newManager(adminToken);
    api.assignManagers(facilityId, adminToken, manager.id());
    roomA = createRoom(facilityId, "Room A", 6);
    roomB = createRoom(facilityId, "Room B", 6);
    tomorrow = LocalDate.now(SGT).plusDays(1);
  }

  @Test
  void aStudentBooksAFreeSlot() {
    var student = api.newStudent();

    var response = book(student, roomA, tomorrow, "10:00", "11:00", 4);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var body = response.getBody();
    assertThat(response.getHeaders().getLocation())
        .hasPath("/api/v1/bookings/" + body.path("id").asText());
    assertThat(body.path("status").asText()).isEqualTo("CONFIRMED");
    assertThat(body.path("roomName").asText()).isEqualTo("Room A");
    assertThat(body.path("startAt").asText()).isEqualTo(tomorrow + "T10:00:00+08:00");
    assertThat(body.path("checkInOpensAt").asText()).isEqualTo(tomorrow + "T09:50:00+08:00");
    assertThat(body.path("checkInClosesAt").asText()).isEqualTo(tomorrow + "T10:15:00+08:00");
    assertThat(body.has("userName")).isFalse();
    var mine = api.get("/api/v1/bookings/mine", student.token()).getBody();
    assertThat(mine.path("items").findValuesAsText("id")).contains(body.path("id").asText());
  }

  @Test
  void onlyOneOfTwoOverlappingBookingsSucceeds() {
    var first = api.newStudent();
    var second = api.newStudent();
    book(first, roomA, tomorrow, "15:00", "16:00", 2);

    var overlapping = book(second, roomA, tomorrow, "15:30", "16:30", 2);
    var backToBack = book(second, roomA, tomorrow, "16:00", "17:00", 2);

    assertProblem(overlapping, HttpStatus.CONFLICT, "SLOT_TAKEN");
    assertThat(backToBack.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void requestsThatBreakARuleAreRejectedWithItsCode() {
    var student = api.newStudent();
    api.patch("/api/v1/rooms/" + roomB, Map.of("active", false), manager.token());

    assertProblem(book(student, roomA, tomorrow, "10:15", "11:00", 2), 422, "NOT_ALIGNED");
    assertProblem(book(student, roomA, tomorrow, "10:00", "12:30", 2), 422, "BOOKING_TOO_LONG");
    assertProblem(
        book(student, roomA, tomorrow, "21:30", "22:30", 2), 422, "OUTSIDE_OPENING_HOURS");
    assertProblem(
        book(student, roomA, tomorrow.minusDays(2), "10:00", "11:00", 2),
        422,
        "OUTSIDE_BOOKING_WINDOW");
    assertProblem(
        book(student, roomA, tomorrow.plusDays(15), "10:00", "11:00", 2),
        422,
        "OUTSIDE_BOOKING_WINDOW");
    assertProblem(book(student, roomA, tomorrow, "10:00", "11:00", 7), 422, "OVER_CAPACITY");
    assertProblem(book(student, roomB, tomorrow, "10:00", "11:00", 2), 422, "ROOM_INACTIVE");
  }

  @Test
  void malformedRequestsAndUnknownRoomsAreRejected() {
    var student = api.newStudent();
    var noOffset = new HashMap<String, Object>();
    noOffset.put("roomId", roomA);
    noOffset.put("startAt", tomorrow + "T10:00:00");
    noOffset.put("endAt", tomorrow + "T11:00:00");
    noOffset.put("attendees", 2);

    assertProblem(
        api.post("/api/v1/bookings", noOffset, student.token()), 400, "VALIDATION_FAILED");
    assertProblem(
        book(student, UUID.randomUUID().toString(), tomorrow, "10:00", "11:00", 2),
        404,
        "NOT_FOUND");
  }

  @Test
  void aStudentHoldsAtMostThreeUpcomingBookings() {
    var student = api.newStudent();
    book(student, roomA, tomorrow, "08:00", "09:00", 1);
    book(student, roomA, tomorrow, "09:00", "10:00", 1);
    book(student, roomA, tomorrow.plusDays(1), "08:00", "09:00", 1);

    var fourth = book(student, roomA, tomorrow.plusDays(2), "08:00", "09:00", 1);

    assertProblem(fourth, 422, "QUOTA_UPCOMING_EXCEEDED");
  }

  @Test
  void aStudentBooksAtMostFourHoursADay() {
    var student = api.newStudent();
    book(student, roomA, tomorrow, "12:00", "14:00", 1);
    book(student, roomB, tomorrow, "14:00", "16:00", 1);

    var more = book(student, roomA, tomorrow, "16:00", "16:30", 1);

    assertProblem(more, 422, "QUOTA_DAILY_HOURS_EXCEEDED");
  }

  @Test
  void aStudentCannotHoldTwoBookingsAtTheSameTime() {
    var student = api.newStudent();
    book(student, roomA, tomorrow, "10:00", "11:00", 1);

    var elsewhere = book(student, roomB, tomorrow, "10:30", "11:30", 1);

    assertProblem(elsewhere, 422, "STUDENT_OVERLAP");
  }

  @Test
  void managersHaveNoQuotas() {
    for (var hour = 8; hour < 13; hour++) {
      var start = String.format("%02d:00", hour);
      var end = String.format("%02d:00", hour + 1);
      assertThat(book(manager, roomA, tomorrow, start, end, 1).getStatusCode())
          .isEqualTo(HttpStatus.CREATED);
    }
  }

  @Test
  void anOwnerCancelsAndTheSlotIsFreeAgain() {
    var owner = api.newStudent();
    var other = api.newStudent();
    var id = book(owner, roomA, tomorrow, "18:00", "19:00", 2).getBody().path("id").asText();

    var cancelled = cancel(id, owner.token(), null);

    assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(cancelled.getBody().path("status").asText()).isEqualTo("CANCELLED");
    assertThat(cancelled.getBody().path("lateCancellation").asBoolean()).isFalse();
    assertThat(book(other, roomA, tomorrow, "18:00", "19:00", 2).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertProblem(cancel(id, owner.token(), null), 422, "NOT_CANCELLABLE");
  }

  @Test
  void cancellingWithinTwoHoursOfTheStartIsRecordedAsLate() {
    var owner = api.newStudent();
    var start = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MINUTES);
    var id = rows.confirmed(roomA, owner.id(), start, start.plus(Duration.ofHours(1)));

    var cancelled = cancel(id, owner.token(), "Feeling unwell");

    assertThat(cancelled.getBody().path("lateCancellation").asBoolean()).isTrue();
    assertThat(cancelled.getBody().path("cancelReason").asText()).isEqualTo("Feeling unwell");
  }

  @Test
  void anOwnerCannotCancelABookingThatHasStarted() {
    var owner = api.newStudent();
    var start = Instant.now().minus(Duration.ofMinutes(5));
    var id = rows.confirmed(roomA, owner.id(), start, start.plus(Duration.ofHours(1)));

    assertProblem(cancel(id, owner.token(), null), 422, "ALREADY_STARTED");
  }

  @Test
  void managersCancelAnyActiveBookingWithAReason() {
    var owner = api.newStudent();
    var id = book(owner, roomA, tomorrow, "20:00", "21:00", 2).getBody().path("id").asText();

    var noReason = cancel(id, manager.token(), null);
    var withReason = cancel(id, manager.token(), "Room flooded");

    assertProblem(noReason, 422, "REASON_REQUIRED");
    assertThat(withReason.getBody().path("status").asText()).isEqualTo("CANCELLED");
    assertThat(withReason.getBody().path("cancelReason").asText()).isEqualTo("Room flooded");
  }

  @Test
  void otherPeoplesBookingsAreNotVisibleToStudents() {
    var owner = api.newStudent();
    var stranger = api.newStudent();
    var id = book(owner, roomA, tomorrow, "13:00", "14:00", 2).getBody().path("id").asText();

    assertProblem(api.get("/api/v1/bookings/" + id, stranger.token()), 404, "NOT_FOUND");
    assertProblem(cancel(id, stranger.token(), "Mine now"), 404, "NOT_FOUND");
    var asManager = api.get("/api/v1/bookings/" + id, manager.token()).getBody();
    assertThat(asManager.path("userName").asText()).isEqualTo("Test Student");
  }

  /**
   * ADR-0004: a booking nobody checked into stops blocking the room 15 minutes after its start, on
   * the very next request, with no background job. Without the settle step the exclusion constraint
   * would still see the stale CONFIRMED row and refuse the new booking.
   */
  @Test
  void aNoShowFreesTheRestOfItsSlotWithoutAnyJobRunning() {
    var nowSgt = OffsetDateTime.now(SGT);
    assumeTrue(nowSgt.toLocalTime().isBefore(LocalTime.of(23, 0)), "needs a free slot today");
    var allDay = api.createFacility(adminToken);
    openEveryDay(allDay, "00:00", "23:30");
    var room = createRoom(allDay, "Late Room", 4);
    var absent = api.newStudent();
    var halfHour =
        nowSgt.truncatedTo(ChronoUnit.HOURS).plusMinutes(nowSgt.getMinute() < 30 ? 0 : 30);
    var start = halfHour.minusMinutes(30).toInstant();
    var noShow = rows.confirmed(room, absent.id(), start, start.plus(Duration.ofHours(2)));

    var seen = api.get("/api/v1/bookings/" + noShow, absent.token()).getBody();
    var latecomer = api.newStudent();
    var next = halfHour.plusMinutes(30);
    var rebooked =
        api.post(
            "/api/v1/bookings",
            Map.of(
                "roomId",
                room,
                "startAt",
                next.toString(),
                "endAt",
                next.plusMinutes(30).toString(),
                "attendees",
                1),
            latecomer.token());

    assertThat(seen.path("status").asText()).isEqualTo("NO_SHOW");
    assertThat(rebooked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(rows.status(noShow)).isEqualTo("NO_SHOW");
  }

  @Test
  void threeNoShowsSuspendAStudentUntilAnAdminLiftsIt() {
    var student = api.newStudent();
    for (var daysAgo = 2; daysAgo <= 4; daysAgo++) {
      var start = Instant.now().minus(Duration.ofDays(daysAgo)).truncatedTo(ChronoUnit.HOURS);
      rows.confirmed(roomA, student.id(), start, start.plus(Duration.ofHours(1)));
    }

    var blocked = book(student, roomA, tomorrow, "10:00", "11:00", 1);
    var me = api.get("/api/v1/me", student.token()).getBody();
    api.patch("/api/v1/admin/users/" + student.id(), Map.of("liftSuspension", true), adminToken);
    var allowed = book(student, roomA, tomorrow, "10:00", "11:00", 1);

    assertProblem(blocked, 403, "SUSPENDED");
    assertThat(me.path("suspendedUntil").asText()).isNotBlank();
    assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void myBookingsSplitUpcomingFromPast() {
    var student = api.newStudent();
    var upcoming =
        book(student, roomA, tomorrow, "11:00", "12:00", 1).getBody().path("id").asText();
    var weekAgo = Instant.now().minus(Duration.ofDays(7)).truncatedTo(ChronoUnit.HOURS);
    var noShow = rows.recordedNoShow(roomA, student.id(), weekAgo, weekAgo.plusSeconds(3600));
    var cancelled =
        rows.cancelled(
            roomA,
            student.id(),
            weekAgo.plus(Duration.ofDays(1)),
            weekAgo.plus(Duration.ofDays(1)).plusSeconds(3600));

    var upcomingIds = ids(api.get("/api/v1/bookings/mine", student.token()).getBody());
    var pastIds = ids(api.get("/api/v1/bookings/mine?scope=past", student.token()).getBody());

    assertThat(upcomingIds).containsExactly(upcoming);
    assertThat(pastIds).containsExactly(cancelled, noShow);
  }

  @Test
  void managersListBookingsInTheirFacilitiesOnly() {
    var student = api.newStudent();
    var booked = book(student, roomA, tomorrow, "17:00", "18:00", 1).getBody().path("id").asText();
    var hourAgo = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MINUTES);
    var noShow = rows.confirmed(roomB, student.id(), hourAgo, hourAgo.plusSeconds(1800));
    var otherManager = api.newManager(adminToken);
    var otherFacility = api.createFacility(adminToken);
    api.assignManagers(otherFacility, adminToken, otherManager.id());

    var all = api.get("/api/v1/bookings?facilityId=" + facilityId, manager.token()).getBody();
    var noShows =
        api.get("/api/v1/bookings?facilityId=" + facilityId + "&status=NO_SHOW", manager.token())
            .getBody();
    var mineByDefault = api.get("/api/v1/bookings", manager.token()).getBody();

    assertThat(ids(all)).contains(booked, noShow);
    assertThat(all.path("items").findValuesAsText("userName")).contains("Test Student");
    assertThat(ids(noShows)).containsExactly(noShow);
    assertThat(ids(mineByDefault)).contains(booked, noShow);
    assertProblem(
        api.get("/api/v1/bookings?facilityId=" + facilityId, otherManager.token()),
        403,
        "FORBIDDEN");
    assertThat(ids(api.get("/api/v1/bookings", otherManager.token()).getBody())).isEmpty();
    assertProblem(api.get("/api/v1/bookings", student.token()), 403, "FORBIDDEN");
  }

  @Test
  void theTimelineShowsFreeBookedAndOwnCells() {
    var owner = api.newStudent();
    var other = api.newStudent();
    var id = book(owner, roomA, tomorrow, "10:00", "11:00", 1).getBody().path("id").asText();

    var forOwner = availability(facilityId, tomorrow, owner.token());
    var forOther = availability(facilityId, tomorrow, other.token());
    var forManager = availability(facilityId, tomorrow, manager.token());

    assertThat(forOwner.path("opensAt").asText()).isEqualTo(tomorrow + "T08:00:00+08:00");
    var ownerCells = cells(forOwner, "Room A");
    assertThat(ownerCells).hasSize(28);
    assertThat(ownerCells.get(4).path("startAt").asText()).isEqualTo(tomorrow + "T10:00:00+08:00");
    assertThat(ownerCells.get(4).path("state").asText()).isEqualTo("MINE");
    assertThat(ownerCells.get(5).path("bookingId").asText()).isEqualTo(id);
    assertThat(ownerCells.get(6).path("state").asText()).isEqualTo("FREE");
    var otherCell = cells(forOther, "Room A").get(4);
    assertThat(otherCell.path("state").asText()).isEqualTo("BOOKED");
    assertThat(otherCell.has("bookingId")).isFalse();
    assertThat(cells(forManager, "Room A").get(4).path("bookingId").asText()).isEqualTo(id);
  }

  @Test
  void theTimelineShowsPastDaysAsPastAndClosedDaysAsEmpty() {
    var student = api.newStudent();
    var closed = api.createFacility(adminToken);
    createRoom(closed, "Closed Room", 4);

    var yesterday = availability(facilityId, tomorrow.minusDays(2), student.token());
    var closedDay = availability(closed, tomorrow, student.token());

    assertThat(cells(yesterday, "Room A"))
        .extracting(c -> c.path("state").asText())
        .containsOnly("PAST");
    assertThat(closedDay.path("opensAt").isNull()).isTrue();
    assertThat(closedDay.path("rooms").get(0).path("cells")).isEmpty();
  }

  private ResponseEntity<JsonNode> book(
      TestUser user, String roomId, LocalDate date, String start, String end, int attendees) {
    return api.post(
        "/api/v1/bookings",
        Map.of(
            "roomId",
            roomId,
            "startAt",
            sgt(date, start),
            "endAt",
            sgt(date, end),
            "attendees",
            attendees),
        user.token());
  }

  private ResponseEntity<JsonNode> cancel(String bookingId, String token, String reason) {
    var body = new HashMap<String, Object>();
    if (reason != null) {
      body.put("reason", reason);
    }
    return api.post("/api/v1/bookings/" + bookingId + "/cancel", body, token);
  }

  private JsonNode availability(String facility, LocalDate date, String token) {
    var response = api.get("/api/v1/facilities/" + facility + "/availability?date=" + date, token);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response.getBody();
  }

  private void openEveryDay(String facility, String opens, String closes) {
    var week = new ArrayList<Map<String, String>>();
    for (var day : DayOfWeek.values()) {
      week.add(Map.of("dayOfWeek", day.name(), "opensAt", opens, "closesAt", closes));
    }
    var response = api.put("/api/v1/facilities/" + facility + "/opening-hours", week, adminToken);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private String createRoom(String facility, String name, int capacity) {
    var response =
        api.post(
            "/api/v1/facilities/" + facility + "/rooms",
            Map.of("name", name, "type", "STUDY_ROOM", "capacity", capacity),
            adminToken);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody().path("id").asText();
  }

  private static List<JsonNode> cells(JsonNode availability, String roomName) {
    for (var room : availability.path("rooms")) {
      if (room.path("roomName").asText().equals(roomName)) {
        var list = new ArrayList<JsonNode>();
        room.path("cells").forEach(list::add);
        return list;
      }
    }
    throw new AssertionError("No room " + roomName);
  }

  private static List<String> ids(JsonNode page) {
    return page.path("items").findValuesAsText("id");
  }

  private static String sgt(LocalDate date, String time) {
    return date.atTime(LocalTime.parse(time)).atZone(SGT).toOffsetDateTime().toString();
  }

  private static void assertProblem(
      ResponseEntity<JsonNode> response, HttpStatus status, String code) {
    assertProblem(response, status.value(), code);
  }

  private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
    assertThat(response.getStatusCode().value())
        .as(response.getBody().toString())
        .isEqualTo(status);
    assertThat(response.getBody().path("code").asText()).isEqualTo(code);
  }
}
