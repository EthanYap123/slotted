package dev.ethanyap.slotted.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ethanyap.slotted.Api;
import dev.ethanyap.slotted.Api.TestUser;
import dev.ethanyap.slotted.IntegrationTest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** PRD ST-6 and BR-20 to BR-23, end to end against Postgres. */
@IntegrationTest
class SeriesIntegrationTest {

  private static final ZoneId SGT = ZoneId.of("Asia/Singapore");

  @Autowired TestRestTemplate http;
  Api api;
  String adminToken;
  String facilityId;
  String roomId;
  String otherRoomId;
  TestUser manager;
  TestUser student;
  LocalDate tomorrow;

  @BeforeEach
  void setUp() {
    api = new Api(http);
    adminToken = api.adminToken();
    facilityId = api.createFacility(adminToken);
    var week = new ArrayList<Map<String, String>>();
    for (var day : DayOfWeek.values()) {
      week.add(Map.of("dayOfWeek", day.name(), "opensAt", "08:00", "closesAt", "22:00"));
    }
    api.put("/api/v1/facilities/" + facilityId + "/opening-hours", week, adminToken);
    manager = api.newManager(adminToken);
    api.assignManagers(facilityId, adminToken, manager.id());
    roomId = createRoom("Room A");
    otherRoomId = createRoom("Room B");
    student = api.newStudent();
    tomorrow = LocalDate.now(SGT).plusDays(1);
  }

  @Test
  void thePreviewReportsEachDateAndSavesNothing() {
    book(api.newStudent(), roomId, tomorrow.plusWeeks(1), "10:00", "11:00");

    var preview = post("/preview", series(tomorrow, "WEEKLY", 4), student);

    assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
    var body = preview.getBody();
    assertThat(body.path("bookableCount").asInt()).isEqualTo(3);
    assertThat(body.path("occurrences").findValuesAsText("bookable"))
        .containsExactly("true", "false", "true", "true");
    var clash = body.path("occurrences").get(1);
    assertThat(clash.path("reason").asText()).isEqualTo("SLOT_TAKEN");
    assertThat(clash.path("detail").asText()).isNotBlank();
    assertThat(mine(student)).isEmpty();
  }

  @Test
  void savingBooksTheAvailableDatesAndListsTheSkippedOnes() {
    book(api.newStudent(), roomId, tomorrow.plusWeeks(1), "10:00", "11:00");

    var created = post("", series(tomorrow, "WEEKLY", 4), student);

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var body = created.getBody();
    var id = body.path("id").asText();
    assertThat(created.getHeaders().getLocation()).hasPath("/api/v1/booking-series/" + id);
    assertThat(body.path("bookings")).hasSize(3);
    assertThat(body.path("skipped")).hasSize(1);
    assertThat(body.path("skipped").get(0).path("startAt").asText())
        .startsWith(tomorrow.plusWeeks(1).toString());
    assertThat(body.path("bookings").get(1).path("startAt").asText())
        .isEqualTo(tomorrow.plusWeeks(2) + "T10:00:00+08:00");
    var first = mine(student).get(0);
    assertThat(first.path("seriesId").asText()).isEqualTo(id);
    assertThat(first.path("source").asText()).isEqualTo("SERIES");
    assertThat(api.get("/api/v1/booking-series/" + id, student.token()).getBody().path("bookings"))
        .hasSize(3);
  }

  @Test
  void aSeriesWithNothingBookableIsRefusedAndSavesNothing() {
    var other = api.newStudent();
    book(other, roomId, tomorrow, "10:00", "11:00");
    book(other, roomId, tomorrow.plusWeeks(1), "10:00", "11:00");

    assertProblem(post("", series(tomorrow, "WEEKLY", 2), student), 422, "SERIES_NOTHING_BOOKABLE");
    assertThat(mine(student)).isEmpty();
  }

  @Test
  void datesMayPassTheBookingWindowButNotTheSeriesLimit() {
    var acrossTheWindow = post("", series(tomorrow, "WEEKLY", 8), student);
    var tooFar = post("/preview", series(tomorrow.plusDays(10), "WEEKLY", 8), api.newStudent());
    var tooMany = post("/preview", series(tomorrow, "WEEKLY", 9), api.newStudent());
    var tooFew = post("/preview", series(tomorrow, "WEEKLY", 1), api.newStudent());

    assertThat(acrossTheWindow.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(acrossTheWindow.getBody().path("bookings")).hasSize(8);
    assertProblem(tooFar, 422, "SERIES_TOO_FAR");
    assertProblem(tooMany, 400, "VALIDATION_FAILED");
    assertProblem(tooFew, 400, "VALIDATION_FAILED");
  }

  @Test
  void studentsRepeatWeeklyAndStaffCanRepeatDaily() {
    assertProblem(post("/preview", series(tomorrow, "DAILY", 3), student), 422, "SERIES_LIMIT");

    var daily = post("", series(tomorrow, "DAILY", 3), manager);

    assertThat(daily.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(daily.getBody().path("bookings").get(1).path("startAt").asText())
        .startsWith(tomorrow.plusDays(1).toString());
  }

  @Test
  void aStudentHoldsOneSeriesWhichCountsAsOneBooking() {
    post("", series(tomorrow, "WEEKLY", 4), student);

    assertProblem(
        post("/preview", series(tomorrow, "WEEKLY", 2, otherRoomId, "14:00", "15:00"), student),
        422,
        "SERIES_LIMIT");
    assertThat(book(student, otherRoomId, tomorrow, "14:00", "15:00").getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(book(student, otherRoomId, tomorrow, "15:00", "16:00").getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertProblem(
        book(student, otherRoomId, tomorrow, "16:00", "17:00"), 422, "QUOTA_UPCOMING_EXCEEDED");
  }

  @Test
  void aStudentAtTheQuotaCannotStartASeries() {
    book(student, roomId, tomorrow, "08:00", "09:00");
    book(student, roomId, tomorrow, "09:00", "10:00");
    book(student, roomId, tomorrow.plusDays(1), "09:00", "10:00");

    assertProblem(
        post("/preview", series(tomorrow.plusDays(2), "WEEKLY", 2), student),
        422,
        "QUOTA_UPCOMING_EXCEEDED");
  }

  @Test
  void eachDateFollowsTheStudentsOtherLimits() {
    var longRoom =
        api.post(
                "/api/v1/facilities/" + facilityId + "/rooms",
                Map.of(
                    "name",
                    "Long Room",
                    "type",
                    "STUDY_ROOM",
                    "capacity",
                    6,
                    "maxBookingMinutes",
                    240),
                adminToken)
            .getBody()
            .path("id")
            .asText();
    book(student, otherRoomId, tomorrow, "10:00", "11:00");
    book(student, longRoom, tomorrow.plusWeeks(1), "12:00", "16:00");

    var preview = post("/preview", series(tomorrow, "WEEKLY", 2), student).getBody();

    assertThat(preview.path("occurrences").findValuesAsText("reason"))
        .containsExactly("STUDENT_OVERLAP", "QUOTA_DAILY_HOURS_EXCEEDED");
  }

  @Test
  void aBlackoutSkipsTheDatesItCovers() {
    api.post(
        "/api/v1/facilities/" + facilityId + "/blackouts",
        Map.of(
            "startAt", sgt(tomorrow.plusWeeks(1), "09:00"),
            "endAt", sgt(tomorrow.plusWeeks(1), "12:00"),
            "reason", "Exams"),
        adminToken);

    var preview = post("/preview", series(tomorrow, "WEEKLY", 2), student).getBody();

    assertThat(preview.path("bookableCount").asInt()).isEqualTo(1);
    assertThat(preview.path("occurrences").get(1).path("reason").asText()).isEqualTo("BLACKOUT");
  }

  @Test
  void oneDateCanBeCancelledAndTheRestRemain() {
    var created = post("", series(tomorrow, "WEEKLY", 3), student).getBody();
    var second = created.path("bookings").get(1).path("id").asText();

    var cancelled = api.post("/api/v1/bookings/" + second + "/cancel", Map.of(), student.token());

    assertThat(cancelled.getBody().path("status").asText()).isEqualTo("CANCELLED");
    assertThat(mine(student)).hasSize(2);
  }

  @Test
  void theSeriesCanBeCancelledFromADateOnwardsThenAnotherStarted() {
    var id = post("", series(tomorrow, "WEEKLY", 3), student).getBody().path("id").asText();

    var cancelled =
        api.post(
            "/api/v1/booking-series/" + id + "/cancel",
            Map.of("fromDate", tomorrow.plusWeeks(1).toString()),
            student.token());

    assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(cancelled.getBody().path("bookings").findValuesAsText("status"))
        .containsExactly("CONFIRMED", "CANCELLED", "CANCELLED");

    api.post(
        "/api/v1/booking-series/" + id + "/cancel",
        Map.of("fromDate", tomorrow.toString()),
        student.token());
    assertProblem(
        api.post(
            "/api/v1/booking-series/" + id + "/cancel",
            Map.of("fromDate", tomorrow.toString()),
            student.token()),
        422,
        "NOT_CANCELLABLE");
    assertThat(post("", series(tomorrow, "WEEKLY", 2), student).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void otherStudentsCannotSeeASeriesAndStaffMustGiveAReason() {
    var id = post("", series(tomorrow, "WEEKLY", 2), student).getBody().path("id").asText();
    var path = "/api/v1/booking-series/" + id;
    var stranger = api.newStudent();

    assertProblem(api.get(path, stranger.token()), 404, "NOT_FOUND");
    assertProblem(
        api.post(path + "/cancel", Map.of("fromDate", tomorrow.toString()), stranger.token()),
        404,
        "NOT_FOUND");
    assertProblem(
        api.post(path + "/cancel", Map.of("fromDate", tomorrow.toString()), manager.token()),
        422,
        "REASON_REQUIRED");

    var cancelled =
        api.post(
            path + "/cancel",
            Map.of("fromDate", tomorrow.toString(), "reason", "Renovation"),
            manager.token());

    assertThat(cancelled.getBody().path("bookings").findValuesAsText("cancelReason"))
        .containsOnly("Renovation");
    assertThat(api.get(path, manager.token()).getBody().path("bookings").get(0).has("userName"))
        .isTrue();
  }

  private Map<String, Object> series(LocalDate first, String frequency, int count) {
    return series(first, frequency, count, roomId, "10:00", "11:00");
  }

  private Map<String, Object> series(
      LocalDate first, String frequency, int count, String room, String start, String end) {
    return Map.of(
        "roomId",
        room,
        "firstStartAt",
        sgt(first, start),
        "firstEndAt",
        sgt(first, end),
        "frequency",
        frequency,
        "occurrences",
        count,
        "attendees",
        2);
  }

  private ResponseEntity<JsonNode> post(String path, Map<String, Object> body, TestUser user) {
    return api.post("/api/v1/booking-series" + path, body, user.token());
  }

  private ResponseEntity<JsonNode> book(
      TestUser user, String room, LocalDate date, String start, String end) {
    return api.post(
        "/api/v1/bookings",
        Map.of(
            "roomId", room, "startAt", sgt(date, start), "endAt", sgt(date, end), "attendees", 1),
        user.token());
  }

  private List<JsonNode> mine(TestUser user) {
    var items = new ArrayList<JsonNode>();
    api.get("/api/v1/bookings/mine", user.token()).getBody().path("items").forEach(items::add);
    return items;
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

  private static String sgt(LocalDate date, String time) {
    return date.atTime(LocalTime.parse(time)).atZone(SGT).toOffsetDateTime().toString();
  }

  private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
    assertThat(response.getStatusCode().value())
        .as(String.valueOf(response.getBody()))
        .isEqualTo(status);
    assertThat(response.getBody().path("code").asText()).isEqualTo(code);
  }
}
