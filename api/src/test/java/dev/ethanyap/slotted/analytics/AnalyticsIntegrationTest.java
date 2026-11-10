package dev.ethanyap.slotted.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ethanyap.slotted.Api;
import dev.ethanyap.slotted.Api.TestUser;
import dev.ethanyap.slotted.IntegrationTest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** PRD AD-4: utilisation and attendance, scoped to the facilities a manager manages. */
@IntegrationTest
class AnalyticsIntegrationTest {

  private static final ZoneId SGT = ZoneId.of("Asia/Singapore");

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  Api api;
  String adminToken;
  TestUser manager;
  String managedFacility;
  String managedRoom;
  String otherFacility;
  String otherRoom;
  LocalDate yesterday;

  @BeforeEach
  void setUp() {
    api = new Api(http);
    adminToken = api.adminToken();
    manager = api.newManager(adminToken);
    managedFacility = api.createFacility(adminToken);
    api.assignManagers(managedFacility, adminToken, manager.id());
    setHours(managedFacility);
    managedRoom = createRoom(managedFacility, "Room A");
    otherFacility = api.createFacility(adminToken);
    setHours(otherFacility);
    otherRoom = createRoom(otherFacility, "Room B");
    yesterday = LocalDate.now(SGT).minusDays(1);
  }

  @Test
  void aRoomsNumbersComeFromItsHoursAndWhatHappenedToItsBookings() {
    var student = api.newStudent();
    insert(managedRoom, student, "10:00", "12:00", "CHECKED_IN", false);
    insert(managedRoom, student, "12:00", "13:00", "NO_SHOW", false);
    insert(managedRoom, student, "14:00", "15:00", "CANCELLED", true);
    // Confirmed, never checked in, now long past: a no-show nobody has written down yet.
    insert(managedRoom, student, "16:00", "17:00", "CONFIRMED", false);

    var report = report(adminToken, managedFacility, yesterday, yesterday);

    assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
    var room = report.getBody().path("rooms").get(0);
    assertThat(report.getBody().path("rooms")).hasSize(1);
    assertThat(room.path("roomId").asText()).isEqualTo(managedRoom);
    assertThat(room.path("facilityId").asText()).isEqualTo(managedFacility);
    assertThat(room.path("openHours").asDouble()).isEqualTo(14.0);
    assertThat(room.path("bookedHours").asDouble()).isEqualTo(2.0);
    assertThat(room.path("utilisation").asDouble()).isEqualTo(0.143);
    assertThat(room.path("bookings").asInt()).isEqualTo(3);
    assertThat(room.path("checkInRate").asDouble()).isEqualTo(0.333);
    assertThat(room.path("noShowRate").asDouble()).isEqualTo(0.667);
    assertThat(room.path("cancellations").asInt()).isEqualTo(1);
    assertThat(room.path("lateCancellations").asInt()).isEqualTo(1);

    var heat = report.getBody().path("heatmap");
    assertThat(heat).hasSize(14);
    var day = yesterday.getDayOfWeek().getValue();
    assertThat(cell(heat, day, 10).path("utilisation").asDouble()).isEqualTo(1.0);
    assertThat(cell(heat, day, 12).path("utilisation").asDouble()).isZero();
  }

  @Test
  void aManagerSeesOnlyTheFacilitiesTheyManage() {
    var mine = report(manager.token(), null, yesterday, yesterday);

    assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(mine.getBody().path("rooms").findValuesAsText("roomId"))
        .containsExactly(managedRoom);
    assertThat(report(manager.token(), otherFacility, yesterday, yesterday).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            report(adminToken, otherFacility, yesterday, yesterday)
                .getBody()
                .path("rooms")
                .findValuesAsText("roomId"))
        .containsExactly(otherRoom);
  }

  @Test
  void studentsAndStrangersCannotSeeAnalytics() {
    assertThat(report(null, null, yesterday, yesterday).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(report(api.newStudent().token(), null, yesterday, yesterday).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void theRangeMustBeInOrderAndAtMost92Days() {
    assertThat(report(adminToken, managedFacility, yesterday, yesterday.minusDays(1)))
        .satisfies(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    assertThat(report(adminToken, managedFacility, yesterday.minusDays(92), yesterday))
        .satisfies(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    assertThat(report(adminToken, managedFacility, yesterday.minusDays(91), yesterday))
        .satisfies(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK));
    assertThat(api.get("/api/v1/analytics/utilisation?toDate=" + yesterday, adminToken))
        .satisfies(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void anUnknownFacilityIsNotFound() {
    assertThat(report(adminToken, UUID.randomUUID().toString(), yesterday, yesterday))
        .satisfies(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  private ResponseEntity<JsonNode> report(
      String token, String facilityId, LocalDate from, LocalDate to) {
    var query = "fromDate=" + from + "&toDate=" + to;
    if (facilityId != null) {
      query += "&facilityId=" + facilityId;
    }
    return api.get("/api/v1/analytics/utilisation?" + query, token);
  }

  private static JsonNode cell(JsonNode heat, int dayOfWeek, int hour) {
    for (var cell : heat) {
      if (cell.path("dayOfWeek").asInt() == dayOfWeek && cell.path("hour").asInt() == hour) {
        return cell;
      }
    }
    throw new AssertionError("No heat map cell for day " + dayOfWeek + " hour " + hour);
  }

  /** A booking written straight to the database, so it can be in the past. */
  private void insert(
      String room, TestUser who, String start, String end, String status, boolean late) {
    var startAt = at(start);
    var cancelled = status.equals("CANCELLED") ? startAt.minusSeconds(1800) : null;
    jdbc.update(
        "INSERT INTO booking (id, room_id, user_id, start_at, end_at, status, attendees,"
            + " checked_in_at, cancelled_at, late_cancellation)"
            + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, 1, ?, ?, ?)",
        UUID.randomUUID().toString(),
        room,
        who.id(),
        startAt.atOffset(ZoneOffset.UTC),
        at(end).atOffset(ZoneOffset.UTC),
        status,
        status.equals("CHECKED_IN") ? startAt.atOffset(ZoneOffset.UTC) : null,
        cancelled == null ? null : cancelled.atOffset(ZoneOffset.UTC),
        late);
  }

  private Instant at(String time) {
    return yesterday.atTime(LocalTime.parse(time)).atZone(SGT).toInstant();
  }

  private void setHours(String facilityId) {
    var week = new ArrayList<Map<String, String>>();
    for (var day : DayOfWeek.values()) {
      week.add(Map.of("dayOfWeek", day.name(), "opensAt", "08:00", "closesAt", "22:00"));
    }
    var response = api.put("/api/v1/facilities/" + facilityId + "/opening-hours", week, adminToken);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private String createRoom(String facilityId, String name) {
    return api.post(
            "/api/v1/facilities/" + facilityId + "/rooms",
            Map.of("name", name, "type", "STUDY_ROOM", "capacity", 6),
            adminToken)
        .getBody()
        .path("id")
        .asText();
  }
}
