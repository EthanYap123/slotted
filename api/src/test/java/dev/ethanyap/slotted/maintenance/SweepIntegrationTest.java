package dev.ethanyap.slotted.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ethanyap.slotted.Api;
import dev.ethanyap.slotted.Api.TestUser;
import dev.ethanyap.slotted.IntegrationTest;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** PRD SY-1 and ADR-0004: the scheduled sweep only writes down what is already true. */
@IntegrationTest
class SweepIntegrationTest {

  private static final String SWEEP = "/api/v1/internal/maintenance/sweep";

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  Api api;
  String adminToken;
  String roomId;

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
                Map.of("name", "Room A", "type", "STUDY_ROOM", "capacity", 6),
                adminToken)
            .getBody()
            .path("id")
            .asText();
  }

  @Test
  void withoutTheRightKeyNothingHappens() {
    var start = Instant.now().minus(Duration.ofMinutes(30));
    var overdue = insertBooking(api.newStudent(), start, start.plus(Duration.ofHours(1)));

    assertThat(sweep(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(sweep("wrong").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    var withAToken = new HttpHeaders();
    withAToken.setBearerAuth(adminToken);
    assertThat(api.exchange(HttpMethod.POST, SWEEP, null, null, withAToken).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(bookingStatus(overdue)).isEqualTo("CONFIRMED");
  }

  @Test
  void itRecordsWhatTimeHasMadeTrueAndASecondRunDoesNothing() {
    var holder = api.newStudent();
    var waiter = api.newStudent();
    var start = Instant.now().minus(Duration.ofMinutes(30));
    var tomorrow10 = tomorrowAt(10);
    var overdue = insertBooking(holder, start, tomorrow10.plus(Duration.ofHours(1)));
    var waiting = insertEntry(waiter, tomorrow10, tomorrow10.plus(Duration.ofHours(1)));
    var started = insertEntry(api.newStudent(), start, start.plus(Duration.ofHours(1)));
    var oldToken = insertRefreshToken(holder, Instant.now().minus(Duration.ofDays(3)));
    var liveToken = insertRefreshToken(holder, Instant.now().plus(Duration.ofDays(3)));

    var first = sweep(IntegrationTest.MAINTENANCE_KEY);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    var body = first.getBody();
    assertThat(body.path("ranSweep").asBoolean()).isTrue();
    assertThat(body.path("noShowsSettled").asInt()).isPositive();
    assertThat(body.path("waitlistPromoted").asInt()).isPositive();
    assertThat(body.path("waitlistExpired").asInt()).isPositive();
    assertThat(body.path("rowsPurged").asInt()).isPositive();
    assertThat(body.path("emailsSent").asInt()).isZero();
    assertThat(body.has("durationMs")).isTrue();
    assertThat(bookingStatus(overdue)).isEqualTo("NO_SHOW");
    assertThat(entryStatus(waiting)).isEqualTo("PROMOTED");
    assertThat(entryStatus(started)).isEqualTo("EXPIRED");
    assertThat(tokenExists(oldToken)).isFalse();
    assertThat(tokenExists(liveToken)).isTrue();

    var second = sweep(IntegrationTest.MAINTENANCE_KEY).getBody();
    assertThat(second.path("noShowsSettled").asInt()).isZero();
    assertThat(second.path("waitlistExpired").asInt()).isZero();
    assertThat(second.path("rowsPurged").asInt()).isZero();
  }

  @Test
  void anExpiredTokenAnotherTokenStillPointsToIsKept() {
    var holder = api.newStudent();
    var expired = insertRefreshToken(holder, Instant.now().minus(Duration.ofDays(3)));
    var pointing = insertRefreshToken(holder, Instant.now().plus(Duration.ofDays(3)));
    jdbc.update(
        "UPDATE refresh_token SET replaced_by_id = ?::uuid WHERE id = ?::uuid", expired, pointing);

    assertThat(sweep(IntegrationTest.MAINTENANCE_KEY).getStatusCode()).isEqualTo(HttpStatus.OK);

    assertThat(tokenExists(expired)).isTrue();
    assertThat(tokenExists(pointing)).isTrue();
  }

  @Test
  void aSweepThatFindsAnotherRunningSkips() {
    var start = Instant.now().minus(Duration.ofMinutes(30));
    var overdue = insertBooking(api.newStudent(), start, start.plus(Duration.ofHours(1)));

    var skipped =
        jdbc.execute(
            (ConnectionCallback<JsonNode>)
                connection -> {
                  try (var lock = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
                    lock.setLong(1, 5_310_771_053L);
                    lock.execute();
                  }
                  try {
                    return sweep(IntegrationTest.MAINTENANCE_KEY).getBody();
                  } finally {
                    try (var unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                      unlock.setLong(1, 5_310_771_053L);
                      unlock.execute();
                    }
                  }
                });

    assertThat(skipped.path("ranSweep").asBoolean()).isFalse();
    assertThat(bookingStatus(overdue)).isEqualTo("CONFIRMED");
  }

  private ResponseEntity<JsonNode> sweep(String key) {
    var headers = new HttpHeaders();
    if (key != null) {
      headers.set("X-Maintenance-Key", key);
    }
    return api.exchange(HttpMethod.POST, SWEEP, null, null, headers);
  }

  private String insertBooking(TestUser who, Instant start, Instant end) {
    var id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO booking (id, room_id, user_id, start_at, end_at, status, attendees)"
            + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'CONFIRMED', 1)",
        id,
        roomId,
        who.id(),
        start.atOffset(ZoneOffset.UTC),
        end.atOffset(ZoneOffset.UTC));
    return id;
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

  private String insertRefreshToken(TestUser who, Instant expiresAt) {
    var id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO refresh_token (id, user_id, token_hash, family_id, expires_at)"
            + " VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?)",
        id,
        who.id(),
        (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""),
        UUID.randomUUID().toString(),
        expiresAt.atOffset(ZoneOffset.UTC));
    return id;
  }

  private static Instant tomorrowAt(int hour) {
    var sgt = java.time.ZoneId.of("Asia/Singapore");
    return java.time.LocalDate.now(sgt).plusDays(1).atTime(hour, 0).atZone(sgt).toInstant();
  }

  private String bookingStatus(String id) {
    return jdbc.queryForObject("SELECT status FROM booking WHERE id = ?::uuid", String.class, id);
  }

  private String entryStatus(String id) {
    return jdbc.queryForObject(
        "SELECT status FROM waitlist_entry WHERE id = ?::uuid", String.class, id);
  }

  private boolean tokenExists(String id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT count(*) > 0 FROM refresh_token WHERE id = ?::uuid", Boolean.class, id));
  }
}
