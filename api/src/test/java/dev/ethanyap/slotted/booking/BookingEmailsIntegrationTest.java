package dev.ethanyap.slotted.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ethanyap.slotted.Api;
import dev.ethanyap.slotted.Api.TestUser;
import dev.ethanyap.slotted.FakeEmailSender;
import dev.ethanyap.slotted.IntegrationTest;
import dev.ethanyap.slotted.notification.application.EmailSender.Result;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** The emails booking changes send, through the outbox (ADR-0004), with a fake provider. */
@IntegrationTest
class BookingEmailsIntegrationTest {

  private static final ZoneId SGT = ZoneId.of("Asia/Singapore");

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired FakeEmailSender emails;
  Api api;
  String adminToken;
  String facilityId;
  String roomId;
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
    roomId =
        api.post(
                "/api/v1/facilities/" + facilityId + "/rooms",
                Map.of("name", "Quiet Room", "type", "STUDY_ROOM", "capacity", 6),
                adminToken)
            .getBody()
            .path("id")
            .asText();
    student = api.newStudent();
    tomorrow = LocalDate.now(SGT).plusDays(1);
  }

  @AfterEach
  void restoreSender() {
    emails.succeed();
  }

  @Test
  void bookingSendsAConfirmation() {
    book(student, tomorrow, "10:00", "11:00");

    var email = emails.await(student.email(), "BOOKING_CONFIRMED");

    assertThat(email.subject()).startsWith("Booked: Quiet Room, ");
    assertThat(email.textBody())
        .contains("Your booking is confirmed.")
        .contains("Quiet Room, ")
        .contains("10:00 to 11:00 (Singapore time)")
        .contains("Check in by scanning the QR code")
        .contains("/bookings");
    assertThat(email.htmlBody()).contains("<p>Your booking is confirmed.</p>");
  }

  @Test
  void cancellingSaysWhoCancelledAndWhy() {
    var own = id(book(student, tomorrow, "10:00", "11:00"));
    api.post("/api/v1/bookings/" + own + "/cancel", Map.of(), student.token());
    assertThat(emails.await(student.email(), "BOOKING_CANCELLED").textBody())
        .contains("You cancelled this booking:")
        .doesNotContain("Reason:");

    var other = api.newStudent();
    var theirs = id(book(other, tomorrow, "12:00", "13:00"));
    api.post(
        "/api/v1/bookings/" + theirs + "/cancel", Map.of("reason", "Room flooded"), adminToken);
    assertThat(emails.await(other.email(), "BOOKING_CANCELLED").textBody())
        .contains("This booking has been cancelled:")
        .contains("Reason: Room flooded");
  }

  @Test
  void aStudentGivenASlotFromTheWaitlistIsTold() {
    var holder = api.newStudent();
    var booking = id(book(holder, tomorrow, "10:00", "11:00"));
    api.post(
        "/api/v1/waitlist",
        Map.of(
            "roomId", roomId, "startAt", sgt(tomorrow, "10:00"), "endAt", sgt(tomorrow, "11:00")),
        student.token());

    api.post("/api/v1/bookings/" + booking + "/cancel", Map.of(), holder.token());

    var email = emails.await(student.email(), "WAITLIST_PROMOTED");
    assertThat(email.subject()).startsWith("From the waitlist: Quiet Room");
    assertThat(email.textBody()).contains("now booked for you");
  }

  @Test
  void aBlackoutTellsEachStudentOnceWithTheReason() {
    book(student, tomorrow, "10:00", "11:00");
    book(student, tomorrow, "14:00", "15:00");

    api.post(
        "/api/v1/facilities/" + facilityId + "/blackouts",
        Map.of(
            "startAt", sgt(tomorrow, "08:00"), "endAt", sgt(tomorrow, "22:00"), "reason", "Exams"),
        adminToken);

    var email = emails.await(student.email(), "BOOKING_CANCELLED");
    assertThat(email.subject()).isEqualTo("2 bookings cancelled");
    assertThat(email.textBody()).contains("Reason: Exams").contains("10:00").contains("14:00");
  }

  @Test
  void aRepeatingBookingSendsOneEmailListingTheDates() {
    book(api.newStudent(), tomorrow.plusWeeks(1), "10:00", "11:00");

    api.post(
        "/api/v1/booking-series",
        Map.of(
            "roomId",
            roomId,
            "firstStartAt",
            sgt(tomorrow, "10:00"),
            "firstEndAt",
            sgt(tomorrow, "11:00"),
            "frequency",
            "WEEKLY",
            "occurrences",
            3,
            "attendees",
            1),
        student.token());

    var email = emails.await(student.email(), "SERIES_CONFIRMED");
    assertThat(email.subject()).isEqualTo("Repeating booking: Quiet Room, 2 dates");
    assertThat(email.textBody()).contains("These dates could not be booked:");
    assertThat(emails.to(student.email())).hasSize(1);
  }

  @Test
  void disablingAnAccountTellsThePersonTheirBookingsWereCancelled() {
    book(student, tomorrow, "10:00", "11:00");

    api.patch("/api/v1/admin/users/" + student.id(), Map.of("status", "DISABLED"), adminToken);

    assertThat(emails.await(student.email(), "BOOKING_CANCELLED").textBody())
        .contains("Reason: The account was disabled.");
  }

  @Test
  void aSuspensionIsEmailedOnceHoweverOftenItIsNoticed() {
    var rows = new BookingRows(jdbc);
    for (var daysAgo = 2; daysAgo <= 3; daysAgo++) {
      var start = Instant.now().minus(Duration.ofDays(daysAgo));
      rows.recordedNoShow(roomId, student.id(), start, start.plus(Duration.ofMinutes(30)));
    }
    var start = Instant.now().minus(Duration.ofMinutes(30));
    rows.confirmed(roomId, student.id(), start, start.plus(Duration.ofHours(1)));

    // Anyone's booking attempt in the room records the third no-show first.
    book(api.newStudent(), tomorrow, "10:00", "11:00");
    var email = emails.await(student.email(), "SUSPENDED");
    assertThat(email.subject()).startsWith("Booking paused until ");
    assertThat(email.textBody()).contains("You missed 3 bookings in the last 30 days");

    book(api.newStudent(), tomorrow, "12:00", "13:00");
    sweep();
    assertThat(emails.to(student.email()).stream().filter(e -> e.kind().equals("SUSPENDED")))
        .hasSize(1);
    assertThat(outboxCount(student.email(), "SUSPENDED")).isEqualTo(1);
  }

  @Test
  void reservedDemoDomainsAreNeverSentTo() {
    var demo = registerAt("example.org");

    book(demo, tomorrow, "10:00", "11:00");

    assertThat(awaitStatus(demo.email(), "SKIPPED")).isEqualTo("SKIPPED");
    assertThat(emails.to(demo.email())).isEmpty();
  }

  @Test
  void aChangeThatFailsSendsNothing() {
    book(api.newStudent(), tomorrow, "10:00", "11:00");

    var taken = book(student, tomorrow, "10:00", "11:00");

    assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(outboxCount(student.email(), null)).isZero();
  }

  @Test
  void aBookingSucceedsWhenTheProviderIsDownAndTheSweepSendsItLater() {
    emails.answer(new Result.TryAgain("Brevo answered 503"));

    var booked = book(student, tomorrow, "10:00", "11:00");

    assertThat(booked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    awaitAttempts(student.email(), 1);
    assertThat(statusOf(student.email())).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT next_attempt_at > now() FROM email_outbox WHERE to_address = ?",
                Boolean.class,
                student.email()))
        .isTrue();

    emails.succeed();
    jdbc.update(
        "UPDATE email_outbox SET next_attempt_at = now() WHERE to_address = ?", student.email());
    var swept = sweep();

    assertThat(swept.path("emailsSent").asInt()).isPositive();
    assertThat(statusOf(student.email())).isEqualTo("SENT");
    assertThat(emails.to(student.email())).hasSize(1);
  }

  @Test
  void anEmailTheProviderRefusesIsGivenUpAtOnce() {
    emails.answer(new Result.Refused("Brevo answered 400: invalid email"));

    book(student, tomorrow, "10:00", "11:00");

    assertThat(awaitStatus(student.email(), "FAILED")).isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "SELECT last_error FROM email_outbox WHERE to_address = ?",
                String.class,
                student.email()))
        .contains("invalid email");
  }

  @Test
  void anEmailIsGivenUpAfterFiveTries() {
    emails.answer(new Result.TryAgain("timeout"));
    book(student, tomorrow, "10:00", "11:00");
    awaitAttempts(student.email(), 1);

    for (var i = 2; i <= 5; i++) {
      jdbc.update(
          "UPDATE email_outbox SET next_attempt_at = now() WHERE to_address = ?", student.email());
      sweep();
    }

    assertThat(statusOf(student.email())).isEqualTo("FAILED");
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM email_outbox WHERE to_address = ?",
                Integer.class,
                student.email()))
        .isEqualTo(5);
  }

  private ResponseEntity<JsonNode> book(TestUser who, LocalDate date, String start, String end) {
    return api.post(
        "/api/v1/bookings",
        Map.of(
            "roomId", roomId, "startAt", sgt(date, start), "endAt", sgt(date, end), "attendees", 1),
        who.token());
  }

  private TestUser registerAt(String domain) {
    var email = "demo-" + UUID.randomUUID() + "@" + domain;
    var body =
        api.post(
                "/api/v1/auth/register",
                Map.of("email", email, "password", Api.GOOD_PASSWORD, "fullName", "Demo Student"))
            .getBody();
    return new TestUser(
        body.path("user").path("id").asText(), email, body.path("accessToken").asText());
  }

  private JsonNode sweep() {
    var headers = new HttpHeaders();
    headers.set("X-Maintenance-Key", IntegrationTest.MAINTENANCE_KEY);
    return api.exchange(HttpMethod.POST, "/api/v1/internal/maintenance/sweep", null, null, headers)
        .getBody();
  }

  private int outboxCount(String address, String kind) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM email_outbox WHERE to_address = ?"
            + " AND (CAST(? AS text) IS NULL OR kind = CAST(? AS text))",
        Integer.class,
        address,
        kind,
        kind);
  }

  private String statusOf(String address) {
    return jdbc.queryForObject(
        "SELECT status FROM email_outbox WHERE to_address = ?", String.class, address);
  }

  /** Emails are sent in the background after commit, so wait for the outcome. */
  private String awaitStatus(String address, String expected) {
    var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    String status = null;
    while (System.nanoTime() < deadline) {
      status = outboxCount(address, null) == 0 ? null : statusOf(address);
      if (expected.equals(status)) {
        return status;
      }
      pause();
    }
    return status;
  }

  private void awaitAttempts(String address, int attempts) {
    var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      var found =
          jdbc.queryForList(
              "SELECT attempts FROM email_outbox WHERE to_address = ?", Integer.class, address);
      if (!found.isEmpty() && found.getFirst() >= attempts) {
        return;
      }
      pause();
    }
    throw new AssertionError("The email to " + address + " was not tried " + attempts + " times");
  }

  private static void pause() {
    try {
      Thread.sleep(25);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String id(ResponseEntity<JsonNode> response) {
    return response.getBody().path("id").asText();
  }

  private static String sgt(LocalDate date, String time) {
    return date.atTime(LocalTime.parse(time)).atZone(SGT).toOffsetDateTime().toString();
  }
}
