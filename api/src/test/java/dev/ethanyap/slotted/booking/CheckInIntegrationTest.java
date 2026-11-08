package dev.ethanyap.slotted.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ethanyap.slotted.Api;
import dev.ethanyap.slotted.Api.TestUser;
import dev.ethanyap.slotted.IntegrationTest;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PRD ST-8, MG-5 and BR-15 to BR-17 against Postgres. Bookings near "now" are written straight to
 * the database, because the API only books the future; the exact boundaries are in {@code
 * BookingCheckInTest}.
 */
@IntegrationTest
class CheckInIntegrationTest {

  private static final ZoneId SGT = ZoneId.of("Asia/Singapore");

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  Api api;
  BookingRows rows;
  String adminToken;
  String facilityId;
  String roomId;
  String otherRoomId;
  TestUser manager;
  TestUser student;

  @BeforeEach
  void setUp() {
    api = new Api(http);
    rows = new BookingRows(jdbc);
    adminToken = api.adminToken();
    facilityId = api.createFacility(adminToken);
    var week = new ArrayList<Map<String, String>>();
    for (var day : DayOfWeek.values()) {
      week.add(Map.of("dayOfWeek", day.name(), "opensAt", "00:00", "closesAt", "23:30"));
    }
    api.put("/api/v1/facilities/" + facilityId + "/opening-hours", week, adminToken);
    roomId = createRoom("Room A");
    otherRoomId = createRoom("Room B");
    manager = api.newManager(adminToken);
    api.assignManagers(facilityId, adminToken, manager.id());
    student = api.newStudent();
  }

  // Checking in

  @Test
  void checkingInInsideTheWindowConfirmsPresenceAndScanningAgainIsHarmless() {
    var booking = confirmedFromNow(Duration.ofMinutes(5));

    var checkedIn = checkIn(student, tokenOf(roomId));

    assertThat(checkedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(checkedIn.getBody().path("id").asText()).isEqualTo(booking);
    assertThat(checkedIn.getBody().path("status").asText()).isEqualTo("CHECKED_IN");
    assertThat(checkedIn.getBody().path("checkedInAt").isNull()).isFalse();
    assertThat(rows.status(booking)).isEqualTo("CHECKED_IN");
    var again = checkIn(student, tokenOf(roomId));
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(again.getBody().path("checkedInAt").asText())
        .isEqualTo(checkedIn.getBody().path("checkedInAt").asText());
  }

  @Test
  void checkingInLateButBeforeTheReleaseStillWorks() {
    confirmedFromNow(Duration.ofMinutes(-14));

    assertThat(checkIn(student, tokenOf(roomId)).getBody().path("status").asText())
        .isEqualTo("CHECKED_IN");
  }

  @Test
  void tooEarlySaysWhenCheckInOpens() {
    confirmedFromNow(Duration.ofMinutes(30));

    var early = checkIn(student, tokenOf(roomId));

    assertProblem(early, HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_IN_NOT_OPEN");
    assertThat(early.getBody().path("detail").asText()).startsWith("Check-in opens at ");
  }

  @Test
  void afterFifteenMinutesTheBookingWasReleased() {
    var booking = confirmedFromNow(Duration.ofMinutes(-16));

    assertProblem(
        checkIn(student, tokenOf(roomId)), HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_IN_CLOSED");
    assertThat(rows.status(booking)).isEqualTo("CONFIRMED");
  }

  @Test
  void aNoShowAlreadyWrittenDownCannotBeCheckedIn() {
    var start = Instant.now().minus(Duration.ofMinutes(20));
    rows.recordedNoShow(roomId, student.id(), start, start.plus(Duration.ofHours(1)));

    assertProblem(
        checkIn(student, tokenOf(roomId)), HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_IN_CLOSED");
  }

  @Test
  void theQrCodeOfAnotherRoomFindsNoBooking() {
    confirmedFromNow(Duration.ofMinutes(5));

    var wrongRoom = checkIn(student, tokenOf(otherRoomId));

    assertProblem(wrongRoom, HttpStatus.UNPROCESSABLE_ENTITY, "NO_BOOKING_TO_CHECK_IN");
    assertThat(wrongRoom.getBody().path("detail").asText()).contains("Room B");
  }

  @Test
  void someoneElsesBookingAndCancelledOnesDoNotCount() {
    var start = Instant.now().plus(Duration.ofMinutes(5));
    rows.confirmed(roomId, api.newStudent().id(), start, start.plus(Duration.ofHours(1)));
    rows.cancelled(otherRoomId, student.id(), start, start.plus(Duration.ofHours(1)));

    assertProblem(
        checkIn(student, tokenOf(roomId)),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "NO_BOOKING_TO_CHECK_IN");
    assertProblem(
        checkIn(student, tokenOf(otherRoomId)),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "NO_BOOKING_TO_CHECK_IN");
  }

  @Test
  void anUnknownTokenIsAnInvalidQrCode() {
    assertProblem(
        checkIn(student, "x".repeat(43)), HttpStatus.UNPROCESSABLE_ENTITY, "QR_TOKEN_INVALID");
    assertThat(checkIn(student, "short").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  // QR codes

  @Test
  void rotatingTheTokenMakesTheOldQrCodeStopWorkingAtOnce() {
    confirmedFromNow(Duration.ofMinutes(5));
    var old = tokenOf(roomId);

    var rotated =
        api.post("/api/v1/rooms/" + roomId + "/check-in-token/rotate", null, manager.token());

    assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(tokenOf(roomId)).isNotEqualTo(old).hasSizeGreaterThanOrEqualTo(32);
    assertProblem(checkIn(student, old), HttpStatus.UNPROCESSABLE_ENTITY, "QR_TOKEN_INVALID");
    assertThat(checkIn(student, tokenOf(roomId)).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void managersGetAPrintableQrCodeOthersDoNot() {
    var qr = qr(manager);

    assertThat(qr.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(qr.getHeaders().getContentType()).hasToString("image/svg+xml");
    assertThat(qr.getHeaders().getCacheControl()).contains("no-store");
    assertThat(qr.getBody())
        .startsWith("<svg")
        .contains("Check-in QR code for Room A")
        .contains(">Room A</text>")
        .doesNotContain(tokenOf(roomId));

    assertThat(qr(student).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    var outsider = api.newManager(adminToken);
    assertThat(
            api.post("/api/v1/rooms/" + roomId + "/check-in-token/rotate", null, outsider.token())
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            api.post(
                    "/api/v1/rooms/" + UUID.randomUUID() + "/check-in-token/rotate",
                    null,
                    adminToken)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  // Ending early

  @Test
  void endingEarlyReleasesFromTheNextHalfHourAndPromotesTheQueue() {
    // Real bookings start on a half hour, so this one started at the last one before now.
    var released = nextHalfHour(Instant.now());
    var start = released.minus(Duration.ofMinutes(30));
    var booking = rows.checkedIn(roomId, student.id(), start, start.plus(Duration.ofHours(2)));
    var waiter = api.newStudent();
    var waitStart = released.plus(Duration.ofMinutes(30));
    assumeTrue(
        waitStart.atZone(SGT).toLocalDate().equals(Instant.now().atZone(SGT).toLocalDate())
            && waitStart.atZone(SGT).toLocalTime().isBefore(LocalTime.of(23, 0)),
        "the freed time must be inside today's opening hours");
    var entry = insertEntry(waiter, waitStart, waitStart.plus(Duration.ofMinutes(30)));

    var ended = api.post("/api/v1/bookings/" + booking + "/end", null, student.token());

    assertThat(ended.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(instant(ended.getBody().path("endAt"))).isEqualTo(released);
    assertThat(ended.getBody().path("releasedAt").isNull()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM waitlist_entry WHERE id = ?::uuid", String.class, entry))
        .isEqualTo("PROMOTED");
  }

  @Test
  void endingBeforeTheStartKeepsTheFirstHalfHour() {
    var start = Instant.now().plus(Duration.ofMinutes(5));
    var booking = rows.checkedIn(roomId, student.id(), start, start.plus(Duration.ofHours(1)));

    var ended = api.post("/api/v1/bookings/" + booking + "/end", null, student.token());

    // Postgres keeps microseconds, rounding the last digit.
    assertThat(instant(ended.getBody().path("endAt")))
        .isCloseTo(start.plus(Duration.ofMinutes(30)), within(1, ChronoUnit.MICROS));
  }

  @Test
  void onlyTheOwnersCheckedInBookingCanBeEndedEarly() {
    var start = Instant.now().minus(Duration.ofMinutes(20));
    var notCheckedIn = rows.confirmed(roomId, student.id(), start, start.plus(Duration.ofHours(1)));
    var checkedIn =
        rows.checkedIn(otherRoomId, student.id(), start, start.plus(Duration.ofHours(1)));

    assertProblem(
        api.post("/api/v1/bookings/" + notCheckedIn + "/end", null, student.token()),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "NOT_CHECKED_IN");
    assertProblem(
        api.post("/api/v1/bookings/" + checkedIn + "/end", null, api.newStudent().token()),
        HttpStatus.NOT_FOUND,
        "NOT_FOUND");
    assertProblem(
        api.post("/api/v1/bookings/" + UUID.randomUUID() + "/end", null, student.token()),
        HttpStatus.NOT_FOUND,
        "NOT_FOUND");
  }

  @Test
  void cancellingAStartedBookingPointsToEndingEarly() {
    var start = Instant.now().minus(Duration.ofMinutes(5));
    var booking = rows.checkedIn(roomId, student.id(), start, start.plus(Duration.ofHours(1)));

    var refused = api.post("/api/v1/bookings/" + booking + "/cancel", Map.of(), student.token());

    assertProblem(refused, HttpStatus.UNPROCESSABLE_ENTITY, "ALREADY_STARTED");
    assertThat(refused.getBody().path("detail").asText()).contains("end it early");
  }

  private String confirmedFromNow(Duration startsIn) {
    var start = Instant.now().plus(startsIn);
    return rows.confirmed(roomId, student.id(), start, start.plus(Duration.ofHours(1)));
  }

  /** The QR code as a browser asks for it. */
  private ResponseEntity<String> qr(TestUser who) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(who.token());
    headers.setAccept(List.of(MediaType.valueOf("image/svg+xml"), MediaType.ALL));
    return http.exchange(
        "/api/v1/rooms/" + roomId + "/check-in-qr",
        HttpMethod.GET,
        new HttpEntity<>(headers),
        String.class);
  }

  private ResponseEntity<JsonNode> checkIn(TestUser who, String token) {
    return api.post("/api/v1/check-ins", Map.of("token", token), who.token());
  }

  private String tokenOf(String room) {
    return jdbc.queryForObject(
        "SELECT checkin_token FROM room WHERE id = ?::uuid", String.class, room);
  }

  private String createRoom(String name) {
    return api.post(
            "/api/v1/facilities/" + facilityId + "/rooms",
            Map.of("name", name, "type", "STUDY_ROOM", "capacity", 6, "maxBookingMinutes", 240),
            adminToken)
        .getBody()
        .path("id")
        .asText();
  }

  private String insertEntry(TestUser who, Instant start, Instant end) {
    var id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO waitlist_entry (id, room_id, user_id, start_at, end_at)"
            + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?)",
        id,
        roomId,
        who.id(),
        start.atOffset(ZoneOffset.UTC),
        end.atOffset(ZoneOffset.UTC));
    return id;
  }

  private static Instant nextHalfHour(Instant now) {
    var minutes = now.atZone(SGT).truncatedTo(ChronoUnit.MINUTES);
    return minutes.minusMinutes(minutes.getMinute() % 30).plusMinutes(30).toInstant();
  }

  private static Instant instant(JsonNode node) {
    return OffsetDateTime.parse(node.asText()).toInstant();
  }

  private static void assertProblem(
      ResponseEntity<JsonNode> response, HttpStatus status, String code) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getBody().path("code").asText()).isEqualTo(code);
  }
}
