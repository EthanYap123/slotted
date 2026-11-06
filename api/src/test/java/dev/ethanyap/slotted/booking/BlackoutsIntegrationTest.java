package dev.ethanyap.slotted.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ethanyap.slotted.Api;
import dev.ethanyap.slotted.Api.TestUser;
import dev.ethanyap.slotted.IntegrationTest;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** PRD BR-5, BR-30, BR-31, MG-2, MG-3 and the booking part of AD-1. */
@IntegrationTest
class BlackoutsIntegrationTest {

  private static final ZoneId SGT = ZoneId.of("Asia/Singapore");

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  Api api;
  String adminToken;
  String facilityId;
  String roomA;
  String roomB;
  TestUser manager;
  TestUser student;
  LocalDate tomorrow;

  @BeforeEach
  void setUp() {
    api = new Api(http);
    adminToken = api.adminToken();
    facilityId = api.createFacility(adminToken);
    setHours("08:00", "22:00", false);
    manager = api.newManager(adminToken);
    api.assignManagers(facilityId, adminToken, manager.id());
    roomA = createRoom("Room A");
    roomB = createRoom("Room B");
    student = api.newStudent();
    tomorrow = LocalDate.now(SGT).plusDays(1);
  }

  @Test
  void aRoomBlackoutBlocksThatRoomOnlyAndShowsOnTheTimeline() {
    var created = blackout(roomA, "10:00", "12:00", "Repainting", false, manager.token());

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody().path("reason").asText()).isEqualTo("Repainting");
    var blocked = book(student, roomA, "11:00", "12:00");
    assertProblem(blocked, 422, "BLACKOUT");
    assertThat(blocked.getBody().path("detail").asText()).contains("Repainting");
    assertThat(book(student, roomB, "11:00", "12:00").getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    var listed =
        api.get("/api/v1/facilities/" + facilityId + "/blackouts", student.token()).getBody();
    assertThat(listed.findValuesAsText("reason")).containsExactly("Repainting");
    var day =
        api.get(
                "/api/v1/facilities/" + facilityId + "/availability?date=" + tomorrow,
                student.token())
            .getBody();
    assertThat(stateAt(day, "Room A", "10:00")).isEqualTo("BLACKOUT");
    assertThat(stateAt(day, "Room A", "12:00")).isEqualTo("FREE");
    assertThat(stateAt(day, "Room B", "10:00")).isEqualTo("FREE");
  }

  @Test
  void aFacilityBlackoutBlocksEveryRoom() {
    blackout(null, "14:00", "15:00", "Fire drill", false, manager.token());

    assertProblem(book(student, roomA, "14:00", "15:00"), 422, "BLACKOUT");
    assertProblem(book(student, roomB, "14:30", "15:30"), 422, "BLACKOUT");
    assertThat(book(student, roomA, "15:00", "16:00").getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void aDryRunListsAffectedBookingsAndChangesNothing() {
    var id = bookingId(student, roomA, "10:00", "11:00");

    var preview = blackout(roomA, "09:00", "12:00", "Repainting", true, manager.token());

    assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(preview.getBody().path("applied").asBoolean()).isFalse();
    assertThat(preview.getBody().path("affectedBookings").findValuesAsText("id"))
        .containsExactly(id);
    assertThat(preview.getBody().path("affectedBookings").findValuesAsText("userName"))
        .containsExactly("Test Student");
    assertThat(statusOf(id)).isEqualTo("CONFIRMED");
    assertThat(
            api.get("/api/v1/facilities/" + facilityId + "/blackouts", manager.token()).getBody())
        .isEmpty();
  }

  @Test
  void aBlackoutCancelsTheBookingsInsideItWithItsReason() {
    var inside = bookingId(student, roomA, "10:00", "11:00");
    var other = api.newStudent();
    var outside = bookingId(other, roomA, "12:00", "13:00");
    var otherRoom = bookingId(api.newStudent(), roomB, "10:00", "11:00");

    blackout(roomA, "09:00", "12:00", "Repainting", false, manager.token());

    var cancelled = api.get("/api/v1/bookings/" + inside, student.token()).getBody();
    assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
    assertThat(cancelled.path("cancelReason").asText()).isEqualTo("Repainting");
    assertThat(statusOf(outside)).isEqualTo("CONFIRMED");
    assertThat(statusOf(otherRoom)).isEqualTo("CONFIRMED");
  }

  @Test
  void onlyManagersOfTheFacilityAndAdminsChangeBlackouts() {
    var otherManager = api.newManager(adminToken);
    var id =
        blackout(null, "10:00", "11:00", "Exams", false, adminToken).getBody().path("id").asText();

    assertProblem(blackout(null, "12:00", "13:00", "No", false, student.token()), 403, "FORBIDDEN");
    assertProblem(
        blackout(null, "12:00", "13:00", "No", false, otherManager.token()), 403, "FORBIDDEN");
    assertProblem(deleteBlackout(id, student.token()), 403, "FORBIDDEN");
    assertProblem(deleteBlackout(id, otherManager.token()), 403, "FORBIDDEN");
  }

  @Test
  void invalidBlackoutsAreRejected() {
    assertProblem(
        blackout(roomA, "12:00", "10:00", "Backwards", false, manager.token()),
        400,
        "VALIDATION_FAILED");
    assertProblem(
        blackout(roomA, "10:00", "12:00", " ", false, manager.token()), 400, "VALIDATION_FAILED");
    var longPast = body(roomA, "2020-01-01T10:00:00+08:00", "2020-01-01T12:00:00+08:00", "Over");
    assertProblem(
        api.post("/api/v1/facilities/" + facilityId + "/blackouts", longPast, manager.token()),
        400,
        "VALIDATION_FAILED");
    assertProblem(
        blackout(UUID.randomUUID().toString(), "10:00", "12:00", "Nope", false, manager.token()),
        404,
        "NOT_FOUND");
  }

  @Test
  void removingABlackoutFreesTheTimeButDoesNotRestoreBookings() {
    var cancelled = bookingId(student, roomA, "10:00", "11:00");
    var id =
        blackout(roomA, "10:00", "11:00", "Leak", false, manager.token())
            .getBody()
            .path("id")
            .asText();

    var removed = deleteBlackout(id, manager.token());

    assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(statusOf(cancelled)).isEqualTo("CANCELLED");
    assertThat(book(api.newStudent(), roomA, "10:00", "11:00").getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertProblem(deleteBlackout(id, adminToken), 404, "NOT_FOUND");
  }

  @Test
  void aBlackoutThatHasEndedCannotBeRemoved() {
    var start = Instant.now().minus(Duration.ofDays(2));
    var id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO blackout (id, facility_id, start_at, end_at, reason, created_by)"
            + " VALUES (?::uuid, ?::uuid, ?, ?, 'Old', ?::uuid)",
        id,
        facilityId,
        start.atOffset(ZoneOffset.UTC),
        start.plus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC),
        manager.id());

    assertProblem(deleteBlackout(id, manager.token()), 422, "BLACKOUT_ENDED");
  }

  @Test
  void shorterOpeningHoursPreviewThenCancelTheBookingsThatNoLongerFit() {
    var morning = bookingId(student, roomA, "09:00", "10:00");
    var afternoon = bookingId(api.newStudent(), roomA, "15:00", "16:00");

    var preview = setHours("08:00", "12:00", true);

    assertThat(preview.getBody().path("applied").asBoolean()).isFalse();
    assertThat(preview.getBody().path("affectedBookings").findValuesAsText("id"))
        .containsExactly(afternoon);
    assertThat(statusOf(afternoon)).isEqualTo("CONFIRMED");

    var applied = setHours("08:00", "12:00", false);

    assertThat(applied.getBody().path("applied").asBoolean()).isTrue();
    assertThat(statusOf(afternoon)).isEqualTo("CANCELLED");
    assertThat(statusOf(morning)).isEqualTo("CONFIRMED");
    assertThat(
            api.get("/api/v1/bookings/" + afternoon, adminToken)
                .getBody()
                .path("cancelReason")
                .asText())
        .contains("opening hours");
  }

  @Test
  void disablingAUserCancelsTheirFutureBookingsOnly() {
    var future = bookingId(student, roomA, "10:00", "11:00");
    var rows = new BookingRows(jdbc);
    var started = Instant.now().minus(Duration.ofMinutes(5));
    var underWay = rows.checkedIn(roomB, student.id(), started, started.plus(Duration.ofHours(1)));

    var disabled =
        api.patch("/api/v1/admin/users/" + student.id(), Map.of("status", "DISABLED"), adminToken);

    assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(statusOf(future)).isEqualTo("CANCELLED");
    assertThat(statusOf(underWay)).isEqualTo("CHECKED_IN");
    assertThat(book(api.newStudent(), roomA, "10:00", "11:00").getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void changingARoleLeavesBookingsAlone() {
    var id = bookingId(student, roomA, "10:00", "11:00");

    api.patch("/api/v1/admin/users/" + student.id(), Map.of("role", "MANAGER"), adminToken);

    assertThat(statusOf(id)).isEqualTo("CONFIRMED");
  }

  private ResponseEntity<JsonNode> setHours(String opens, String closes, boolean dryRun) {
    var week = new ArrayList<Map<String, String>>();
    for (var day : DayOfWeek.values()) {
      week.add(Map.of("dayOfWeek", day.name(), "opensAt", opens, "closesAt", closes));
    }
    var response =
        api.put(
            "/api/v1/facilities/" + facilityId + "/opening-hours?dryRun=" + dryRun,
            week,
            adminToken);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response;
  }

  private ResponseEntity<JsonNode> blackout(
      String room, String start, String end, String reason, boolean dryRun, String token) {
    return api.post(
        "/api/v1/facilities/" + facilityId + "/blackouts?dryRun=" + dryRun,
        body(room, sgt(start), sgt(end), reason),
        token);
  }

  private static Map<String, Object> body(String room, String start, String end, String reason) {
    var body = new HashMap<String, Object>();
    body.put("startAt", start);
    body.put("endAt", end);
    body.put("reason", reason);
    if (room != null) {
      body.put("roomId", room);
    }
    return body;
  }

  private ResponseEntity<JsonNode> deleteBlackout(String id, String token) {
    return api.exchange(HttpMethod.DELETE, "/api/v1/blackouts/" + id, null, token);
  }

  private ResponseEntity<JsonNode> book(TestUser user, String room, String start, String end) {
    return api.post(
        "/api/v1/bookings",
        Map.of("roomId", room, "startAt", sgt(start), "endAt", sgt(end), "attendees", 1),
        user.token());
  }

  private String bookingId(TestUser user, String room, String start, String end) {
    var response = book(user, room, start, end);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody().path("id").asText();
  }

  private String statusOf(String bookingId) {
    return new BookingRows(jdbc).status(bookingId);
  }

  private String createRoom(String name) {
    return api.post(
            "/api/v1/facilities/" + facilityId + "/rooms",
            Map.of("name", name, "type", "STUDY_ROOM", "capacity", 6),
            adminToken)
        .getBody()
        .path("id")
        .asText();
  }

  private String sgt(String time) {
    return tomorrow.atTime(LocalTime.parse(time)).atZone(SGT).toOffsetDateTime().toString();
  }

  private String stateAt(JsonNode day, String roomName, String time) {
    var prefix = tomorrow + "T" + time;
    for (var room : day.path("rooms")) {
      if (room.path("roomName").asText().equals(roomName)) {
        for (var cell : room.path("cells")) {
          if (cell.path("startAt").asText().startsWith(prefix)) {
            return cell.path("state").asText();
          }
        }
      }
    }
    throw new AssertionError("No cell for " + roomName + " at " + time + " in " + List.of(day));
  }

  private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
    assertThat(response.getStatusCode().value())
        .as(String.valueOf(response.getBody()))
        .isEqualTo(status);
    assertThat(response.getBody().path("code").asText()).isEqualTo(code);
  }
}
