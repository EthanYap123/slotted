package dev.ethanyap.slotted.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ethanyap.slotted.Api;
import dev.ethanyap.slotted.Api.TestUser;
import dev.ethanyap.slotted.IntegrationTest;
import dev.ethanyap.slotted.audit.application.AuditLog;
import dev.ethanyap.slotted.audit.domain.AuditAction;
import dev.ethanyap.slotted.audit.domain.Change;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** PRD BR-32, BR-33 and AD-3: every change is recorded, and admins can search the records. */
@IntegrationTest
class AuditIntegrationTest {

  private static final ZoneId SGT = ZoneId.of("Asia/Singapore");
  private static final String AUDIT = "/api/v1/admin/audit-log";

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired AuditLog auditLog;
  @Autowired TransactionTemplate transactions;
  Api api;
  String adminToken;
  String facilityId;
  TestUser manager;
  String roomId;
  LocalDate tomorrow;

  @BeforeEach
  void setUp() {
    api = new Api(http);
    adminToken = api.adminToken();
    facilityId = api.createFacility(adminToken);
    manager = api.newManager(adminToken);
    api.assignManagers(facilityId, adminToken, manager.id());
    setHours("08:00", "22:00");
    roomId = createRoom("Room A");
    tomorrow = LocalDate.now(SGT).plusDays(1);
  }

  @Test
  void aBookingsHistoryReadsInOrderAndMarksWhatTheSystemDid() {
    var alice = api.newStudent();
    var bob = api.newStudent();
    var bookingId = book(alice, "10:00", "11:00");
    var entryId =
        api.post(
                "/api/v1/waitlist",
                Map.of("roomId", roomId, "startAt", sgt("10:00"), "endAt", sgt("11:00")),
                bob.token())
            .getBody()
            .path("id")
            .asText();

    api.post("/api/v1/bookings/" + bookingId + "/cancel", Map.of(), alice.token());

    var history = records("entityType=BOOKING&entityId=" + bookingId);
    assertThat(actions(history)).containsExactly("BOOKING_CREATED", "BOOKING_CANCELLED");
    assertThat(history.get(0).path("actorId").asText()).isEqualTo(alice.id());
    assertThat(history.get(0).path("actorName").asText()).isEqualTo("Test Student");
    assertThat(history.get(0).path("before").isNull()).isTrue();
    assertThat(history.get(0).path("after").path("status").asText()).isEqualTo("CONFIRMED");
    assertThat(history.get(1).path("before").path("status").asText()).isEqualTo("CONFIRMED");
    assertThat(history.get(1).path("after").path("status").asText()).isEqualTo("CANCELLED");
    assertThat(history.get(1).path("requestId").asText()).isNotBlank();

    var entry = records("entityType=WAITLIST_ENTRY&entityId=" + entryId);
    assertThat(actions(entry)).containsExactly("WAITLIST_JOINED", "WAITLIST_PROMOTED");
    var promotion = entry.get(1);
    assertThat(promotion.path("actorId").isNull()).isTrue();
    assertThat(promotion.path("actorName").asText()).isEqualTo("System");

    var promotedId = promotion.path("after").path("promotedBookingId").asText();
    var promoted = records("entityId=" + promotedId);
    assertThat(actions(promoted)).containsExactly("BOOKING_PROMOTED");
    assertThat(promoted.getFirst().path("actorName").asText()).isEqualTo("System");
    assertThat(promoted.getFirst().path("after").path("userId").asText()).isEqualTo(bob.id());
  }

  @Test
  void theSweepRecordsNoShowsAsTheSystem() {
    var student = api.newStudent();
    var start = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(2));
    var bookingId = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO booking (id, room_id, user_id, start_at, end_at, status, attendees)"
            + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'CONFIRMED', 1)",
        bookingId,
        roomId,
        student.id(),
        start.atOffset(ZoneOffset.UTC),
        start.plus(Duration.ofMinutes(30)).atOffset(ZoneOffset.UTC));

    sweep();

    var history = records("entityId=" + bookingId);
    assertThat(actions(history)).containsExactly("BOOKING_NO_SHOW");
    assertThat(history.getFirst().path("actorName").asText()).isEqualTo("System");
    assertThat(history.getFirst().path("before").path("status").asText()).isEqualTo("CONFIRMED");
    assertThat(history.getFirst().path("after").path("status").asText()).isEqualTo("NO_SHOW");
  }

  @Test
  void changesToFacilitiesRoomsHoursBlackoutsAndUsersAreRecordedWithWhoMadeThem() {
    var facility = records("entityType=FACILITY&entityId=" + facilityId);
    assertThat(actions(facility)).containsExactly("FACILITY_CREATED", "FACILITY_MANAGERS_CHANGED");
    assertThat(facility.get(1).path("after").path("managerIds").get(0).asText())
        .isEqualTo(manager.id());

    api.patch("/api/v1/rooms/" + roomId, Map.of("capacity", 8), manager.token());
    api.post("/api/v1/rooms/" + roomId + "/check-in-token/rotate", Map.of(), manager.token());
    var room = records("entityType=ROOM&entityId=" + roomId);
    assertThat(actions(room)).containsExactly("ROOM_CREATED", "ROOM_UPDATED", "ROOM_QR_ROTATED");
    assertThat(room.get(1).path("actorId").asText()).isEqualTo(manager.id());
    assertThat(room.get(1).path("before").path("capacity").asInt()).isEqualTo(6);
    assertThat(room.get(1).path("after").path("capacity").asInt()).isEqualTo(8);
    for (var record : room) {
      assertThat(record.toString()).doesNotContain("checkInToken").doesNotContain("checkin");
    }

    var hours = records("entityType=OPENING_HOURS&entityId=" + facilityId);
    assertThat(actions(hours)).containsExactly("OPENING_HOURS_CHANGED");
    assertThat(hours.getFirst().path("before").path("days")).isEmpty();
    assertThat(hours.getFirst().path("after").path("days")).hasSize(7);

    var blackoutId =
        api.post(
                "/api/v1/facilities/" + facilityId + "/blackouts",
                Map.of("startAt", sgt("12:00"), "endAt", sgt("13:00"), "reason", "Cleaning"),
                manager.token())
            .getBody()
            .path("id")
            .asText();
    api.exchange(HttpMethod.DELETE, "/api/v1/blackouts/" + blackoutId, null, manager.token());
    var blackout = records("entityType=BLACKOUT&entityId=" + blackoutId);
    assertThat(actions(blackout)).containsExactly("BLACKOUT_CREATED", "BLACKOUT_DELETED");
    assertThat(blackout.get(1).path("before").path("reason").asText()).isEqualTo("Cleaning");
    assertThat(blackout.get(1).path("after").isNull()).isTrue();

    var user = records("entityType=USER&entityId=" + manager.id());
    assertThat(actions(user)).containsExactly("USER_REGISTERED", "USER_UPDATED");
    assertThat(user.get(1).path("before").path("role").asText()).isEqualTo("STUDENT");
    assertThat(user.get(1).path("after").path("role").asText()).isEqualTo("MANAGER");
    assertThat(user.get(1).toString()).doesNotContain("password");
  }

  @Test
  void withoutAnEntityTheNewestRecordsComeFirst() {
    var student = api.newStudent();
    var bookingId = book(student, "15:00", "16:00");
    api.post("/api/v1/bookings/" + bookingId + "/cancel", Map.of(), student.token());

    var mine = records("actorId=" + student.id());

    assertThat(actions(mine))
        .containsExactly("BOOKING_CANCELLED", "BOOKING_CREATED", "USER_REGISTERED");
    var page = api.get(AUDIT + "?actorId=" + student.id() + "&size=2", adminToken).getBody();
    assertThat(page.path("items")).hasSize(2);
    assertThat(page.path("page").path("totalElements").asInt()).isEqualTo(3);
  }

  @Test
  void onlyAdminsCanReadTheAuditLog() {
    assertThat(api.get(AUDIT, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(api.get(AUDIT, api.newStudent().token()).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(api.get(AUDIT, manager.token()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(api.get(AUDIT, adminToken).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void aRecordIsWrittenInTheChangesTransactionAndRolledBackWithIt() {
    var entityId = UUID.randomUUID();
    var change =
        new Change(AuditAction.ROOM_UPDATED, entityId, null, null, null, Map.of("name", "X"));

    assertThatThrownBy(() -> auditLog.record(change))
        .isInstanceOf(IllegalTransactionStateException.class);
    transactions.executeWithoutResult(
        status -> {
          auditLog.record(change);
          status.setRollbackOnly();
        });

    assertThat(countFor(entityId)).isZero();
  }

  @Test
  void recordsCannotBeChangedAndOldOnesAreDeletedBySweep() {
    var old = UUID.randomUUID();
    var recent = UUID.randomUUID();
    insertRecord(old, Instant.now().minus(Duration.ofDays(181)));
    insertRecord(recent, Instant.now().minus(Duration.ofDays(179)));

    assertThatThrownBy(
            () -> jdbc.update("UPDATE audit_log SET action = 'CHANGED' WHERE entity_id = ?", old))
        .isInstanceOf(DataAccessException.class);

    sweep();

    assertThat(countFor(old)).isZero();
    assertThat(countFor(recent)).isEqualTo(1);
  }

  private void insertRecord(UUID entityId, Instant at) {
    jdbc.update(
        "INSERT INTO audit_log (occurred_at, action, entity_type, entity_id, after)"
            + " VALUES (?, 'ROOM_UPDATED', 'ROOM', ?, '{}'::jsonb)",
        at.atOffset(ZoneOffset.UTC),
        entityId);
  }

  private int countFor(UUID entityId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_id = ?", Integer.class, entityId);
  }

  private List<JsonNode> records(String query) {
    var response = api.get(AUDIT + "?" + query, adminToken);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return StreamSupport.stream(response.getBody().path("items").spliterator(), false).toList();
  }

  private static List<String> actions(List<JsonNode> records) {
    return records.stream().map(r -> r.path("action").asText()).toList();
  }

  private void sweep() {
    var headers = new HttpHeaders();
    headers.set("X-Maintenance-Key", IntegrationTest.MAINTENANCE_KEY);
    var response =
        api.exchange(HttpMethod.POST, "/api/v1/internal/maintenance/sweep", null, null, headers);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private String book(TestUser who, String start, String end) {
    var response =
        api.post(
            "/api/v1/bookings",
            Map.of("roomId", roomId, "startAt", sgt(start), "endAt", sgt(end), "attendees", 1),
            who.token());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody().path("id").asText();
  }

  private void setHours(String opens, String closes) {
    var week = new ArrayList<Map<String, String>>();
    for (var day : DayOfWeek.values()) {
      week.add(Map.of("dayOfWeek", day.name(), "opensAt", opens, "closesAt", closes));
    }
    var response =
        api.put("/api/v1/facilities/" + facilityId + "/opening-hours", week, manager.token());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private String createRoom(String name) {
    return api.post(
            "/api/v1/facilities/" + facilityId + "/rooms",
            Map.of("name", name, "type", "STUDY_ROOM", "capacity", 6),
            manager.token())
        .getBody()
        .path("id")
        .asText();
  }

  private String sgt(String time) {
    return tomorrow.atTime(LocalTime.parse(time)).atZone(SGT).toOffsetDateTime().toString();
  }
}
