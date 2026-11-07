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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PRD ST-7 and BR-24 to BR-28, end to end against Postgres. */
@IntegrationTest
class WaitlistIntegrationTest {

  private static final ZoneId SGT = ZoneId.of("Asia/Singapore");

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  Api api;
  String adminToken;
  String facilityId;
  String roomId;
  String otherRoomId;
  TestUser holder;
  TestUser first;
  TestUser second;
  LocalDate tomorrow;

  @BeforeEach
  void setUp() {
    api = new Api(http);
    adminToken = api.adminToken();
    facilityId = api.createFacility(adminToken);
    api.put("/api/v1/facilities/" + facilityId + "/opening-hours", hours("22:00"), adminToken);
    roomId = createRoom("Room A");
    otherRoomId = createRoom("Room B");
    holder = api.newStudent();
    first = api.newStudent();
    second = api.newStudent();
    tomorrow = LocalDate.now(SGT).plusDays(1);
  }

  // Joining

  @Test
  void joiningATakenSlotShowsThePlaceInTheQueue() {
    book(holder, roomId, tomorrow, "10:00", "11:00");

    var joined = join(first, roomId, tomorrow, "10:00", "11:00");

    assertThat(joined.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var body = joined.getBody();
    assertThat(joined.getHeaders().getLocation())
        .hasPath("/api/v1/waitlist/" + body.path("id").asText());
    assertThat(body.path("status").asText()).isEqualTo("WAITING");
    assertThat(body.path("position").asInt()).isEqualTo(1);
    assertThat(body.path("roomName").asText()).isEqualTo("Room A");
    assertThat(body.path("startAt").asText()).isEqualTo(tomorrow + "T10:00:00+08:00");
    assertThat(join(second, roomId, tomorrow, "10:00", "11:00").getBody().path("position").asInt())
        .isEqualTo(2);
  }

  @Test
  void aSlotThatIsOnlyPartlyTakenCanBeWaitedFor() {
    book(holder, roomId, tomorrow, "10:00", "11:00");

    assertThat(join(first, roomId, tomorrow, "10:30", "11:30").getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void onlyEntriesWantingTheSameTimeCountTowardThePosition() {
    book(holder, roomId, tomorrow, "10:00", "12:00");
    book(holder, roomId, tomorrow, "14:00", "15:00");
    join(first, roomId, tomorrow, "10:00", "11:00");

    assertThat(join(second, roomId, tomorrow, "10:30", "12:00").getBody().path("position").asInt())
        .isEqualTo(2);
    assertThat(
            join(api.newStudent(), roomId, tomorrow, "14:00", "15:00")
                .getBody()
                .path("position")
                .asInt())
        .isEqualTo(1);
  }

  @Test
  void aFreeSlotShouldBeBookedInstead() {
    assertProblem(
        join(first, roomId, tomorrow, "10:00", "11:00"),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "SLOT_AVAILABLE");
  }

  @Test
  void theSameSlotCannotBeJoinedTwice() {
    book(holder, roomId, tomorrow, "10:00", "11:00");
    join(first, roomId, tomorrow, "10:00", "11:00");

    assertProblem(
        join(first, roomId, tomorrow, "10:00", "11:00"), HttpStatus.CONFLICT, "ALREADY_WAITING");
  }

  @Test
  void aStudentCanWaitForAtMostThreeSlots() {
    book(holder, roomId, tomorrow, "10:00", "11:00");
    book(holder, roomId, tomorrow, "12:00", "13:00");
    book(holder, roomId, tomorrow, "14:00", "15:00");
    book(second, roomId, tomorrow, "16:00", "17:00");
    join(first, roomId, tomorrow, "10:00", "11:00");
    join(first, roomId, tomorrow, "12:00", "13:00");
    join(first, roomId, tomorrow, "14:00", "15:00");

    assertProblem(
        join(first, roomId, tomorrow, "16:00", "17:00"),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "WAITLIST_LIMIT");
  }

  @Test
  void theSlotMustPassTheBookingRulesExceptBeingFree() {
    book(holder, roomId, tomorrow, "10:00", "11:00");
    book(first, otherRoomId, tomorrow, "10:00", "11:00");

    assertProblem(
        join(first, roomId, tomorrow, "10:00", "11:00"),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "STUDENT_OVERLAP");
    assertProblem(
        join(second, roomId, tomorrow, "10:15", "11:00"),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "NOT_ALIGNED");
    assertProblem(
        join(second, roomId, LocalDate.now(SGT).plusDays(20), "10:00", "11:00"),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "OUTSIDE_BOOKING_WINDOW");
  }

  @Test
  void aSuspendedStudentCannotJoin() {
    book(holder, roomId, tomorrow, "10:00", "11:00");
    recordNoShows(first, 3);

    assertProblem(
        join(first, roomId, tomorrow, "10:00", "11:00"), HttpStatus.FORBIDDEN, "SUSPENDED");
  }

  @Test
  void staffCannotJoin() {
    book(holder, roomId, tomorrow, "10:00", "11:00");
    var manager = api.newManager(adminToken);

    assertProblem(
        join(manager, roomId, tomorrow, "10:00", "11:00"), HttpStatus.FORBIDDEN, "FORBIDDEN");
  }

  @Test
  void anUnknownRoomIsNotFound() {
    assertProblem(
        join(first, UUID.randomUUID().toString(), tomorrow, "10:00", "11:00"),
        HttpStatus.NOT_FOUND,
        "NOT_FOUND");
  }

  @Test
  void slotTakenSaysWhetherTheCallerCanWait() {
    book(holder, roomId, tomorrow, "10:00", "11:00");

    var taken = book(first, roomId, tomorrow, "10:00", "11:00");
    assertProblem(taken, HttpStatus.CONFLICT, "SLOT_TAKEN");
    assertThat(taken.getBody().path("waitlistable").asBoolean()).isTrue();

    join(first, roomId, tomorrow, "10:00", "11:00");
    var alreadyWaiting = book(first, roomId, tomorrow, "10:00", "11:00");
    assertThat(alreadyWaiting.getBody().path("waitlistable").asBoolean()).isFalse();

    var manager = api.newManager(adminToken);
    assertThat(
            book(manager, roomId, tomorrow, "10:00", "11:00")
                .getBody()
                .path("waitlistable")
                .asBoolean())
        .isFalse();
  }

  // Promotion

  @Test
  void cancellingGivesTheSlotToTheFirstInTheQueueAtOnce() {
    var booking = id(book(holder, roomId, tomorrow, "10:00", "11:00"));
    var entry = id(join(first, roomId, tomorrow, "10:00", "11:00"));
    join(second, roomId, tomorrow, "10:00", "11:00");

    cancel(holder, booking);

    var promoted = mine(first).get(0);
    assertThat(promoted.path("status").asText()).isEqualTo("PROMOTED");
    assertThat(promoted.path("id").asText()).isEqualTo(entry);
    assertThat(promoted.path("position").isNull()).isTrue();
    var bookingId = promoted.path("promotedBookingId").asText();
    var created = api.get("/api/v1/bookings/" + bookingId, first.token()).getBody();
    assertThat(created.path("status").asText()).isEqualTo("CONFIRMED");
    assertThat(created.path("source").asText()).isEqualTo("WAITLIST");
    assertThat(created.path("attendees").asInt()).isEqualTo(1);
    assertThat(created.path("startAt").asText()).isEqualTo(tomorrow + "T10:00:00+08:00");
    // The next person moves up and keeps waiting.
    assertThat(mine(second).get(0).path("status").asText()).isEqualTo("WAITING");
    assertThat(mine(second).get(0).path("position").asInt()).isEqualTo(1);
  }

  @Test
  void aPromotedStudentCancellingPromotesTheNextPerson() {
    var booking = id(book(holder, roomId, tomorrow, "10:00", "11:00"));
    join(first, roomId, tomorrow, "10:00", "11:00");
    join(second, roomId, tomorrow, "10:00", "11:00");
    cancel(holder, booking);

    cancel(first, mine(first).get(0).path("promotedBookingId").asText());

    assertThat(mine(second).get(0).path("status").asText()).isEqualTo("PROMOTED");
  }

  @Test
  void aSlotThatIsStillPartlyTakenIsNotPromoted() {
    var early = id(book(holder, roomId, tomorrow, "10:00", "11:00"));
    var late = id(book(holder, roomId, tomorrow, "11:00", "12:00"));
    join(first, roomId, tomorrow, "10:00", "12:00");

    cancel(holder, early);
    assertThat(mine(first).get(0).path("status").asText()).isEqualTo("WAITING");

    cancel(holder, late);
    assertThat(mine(first).get(0).path("status").asText()).isEqualTo("PROMOTED");
  }

  @Test
  void entriesThatDoNotClashAreAllPromotedInOrder() {
    var booking = id(book(holder, roomId, tomorrow, "10:00", "12:00"));
    join(first, roomId, tomorrow, "10:00", "11:00");
    join(second, roomId, tomorrow, "10:30", "11:30");
    var third = api.newStudent();
    join(third, roomId, tomorrow, "11:00", "12:00");

    cancel(holder, booking);

    assertThat(mine(first).get(0).path("status").asText()).isEqualTo("PROMOTED");
    assertThat(mine(second).get(0).path("status").asText()).isEqualTo("WAITING");
    assertThat(mine(third).get(0).path("status").asText()).isEqualTo("PROMOTED");
  }

  @Test
  void aStudentAtTheirQuotaIsSkippedAndKeepsWaiting() {
    var booking = id(book(holder, roomId, tomorrow, "10:00", "11:00"));
    join(first, roomId, tomorrow, "10:00", "11:00");
    join(second, roomId, tomorrow, "10:00", "11:00");
    book(first, otherRoomId, tomorrow.plusDays(1), "09:00", "10:00");
    book(first, otherRoomId, tomorrow.plusDays(2), "09:00", "10:00");
    book(first, otherRoomId, tomorrow.plusDays(3), "09:00", "10:00");

    cancel(holder, booking);

    assertThat(mine(first).get(0).path("status").asText()).isEqualTo("WAITING");
    assertThat(mine(second).get(0).path("status").asText()).isEqualTo("PROMOTED");
  }

  @Test
  void aStudentWithABookingElsewhereAtThatTimeIsSkipped() {
    var booking = id(book(holder, roomId, tomorrow, "10:00", "11:00"));
    join(first, roomId, tomorrow, "10:00", "11:00");
    join(second, roomId, tomorrow, "10:00", "11:00");
    book(first, otherRoomId, tomorrow, "10:00", "11:00");

    cancel(holder, booking);

    assertThat(mine(first).get(0).path("status").asText()).isEqualTo("WAITING");
    assertThat(mine(second).get(0).path("status").asText()).isEqualTo("PROMOTED");
  }

  @Test
  void aStudentSuspendedSinceJoiningIsSkipped() {
    var booking = id(book(holder, roomId, tomorrow, "10:00", "11:00"));
    join(first, roomId, tomorrow, "10:00", "11:00");
    join(second, roomId, tomorrow, "10:00", "11:00");
    recordNoShows(first, 3);

    cancel(holder, booking);

    assertThat(mine(first).get(0).path("status").asText()).isEqualTo("WAITING");
    assertThat(mine(second).get(0).path("status").asText()).isEqualTo("PROMOTED");
  }

  /**
   * ADR-0007: promotion holds the room, so it must not wait for a student who is mid-request. This
   * holds that student's lock while the cancel runs, so a cancel that waited would miss the time
   * limit. A deadlock would not fail anything else: Postgres breaks it and a booking retries. The
   * student who is busy keeps their place, and gets the next chance.
   */
  @Test
  void aStudentWhoIsBusyIsSkippedWithoutWaitingAndKeepsTheirPlace() throws Exception {
    var booking = id(book(holder, roomId, tomorrow, "10:00", "11:00"));
    var other = id(book(holder, roomId, tomorrow, "12:00", "13:00"));
    join(first, roomId, tomorrow, "10:00", "11:00");
    join(second, roomId, tomorrow, "10:00", "11:00");
    join(first, roomId, tomorrow, "12:00", "13:00");

    try (var executor = Executors.newSingleThreadExecutor()) {
      var cancelled = new CompletableFuture<ResponseEntity<JsonNode>>();
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              status -> {
                jdbc.queryForObject(
                    "SELECT id FROM app_user WHERE id = ?::uuid FOR NO KEY UPDATE",
                    String.class,
                    first.id());
                executor.execute(() -> cancelled.complete(cancel(holder, booking)));
                // Holding the lock until the cancel answers, or for the whole time limit.
                assertThat(cancelled)
                    .as("the cancel must not wait for a student who is mid-request")
                    .succeedsWithin(Duration.ofSeconds(10));
              });
      assertThat(cancelled.get().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    assertThat(mine(second).get(0).path("status").asText()).isEqualTo("PROMOTED");
    assertThat(mine(first).stream().map(e -> e.path("status").asText())).containsOnly("WAITING");

    cancel(holder, other);

    assertThat(mine(first).stream().map(e -> e.path("status").asText()))
        .containsExactlyInAnyOrder("WAITING", "PROMOTED");
  }

  /**
   * ADR-0004 and ADR-0007: a no-show frees the room with no request to trigger anything. The next
   * person to try gets {@code SLOT_TAKEN} and the waiting student gets the slot, even though the
   * failed booking rolls back.
   */
  @Test
  void aNoShowGoesToTheQueueBeforeTheNextPersonTryingToBook() {
    var rows = new BookingRows(jdbc);
    var overdue = Instant.now().minus(Duration.ofMinutes(20));
    var noShow = rows.confirmed(roomId, holder.id(), overdue, sgtInstant(tomorrow, "11:00"));
    insertEntry(first, sgtInstant(tomorrow, "10:00"), sgtInstant(tomorrow, "11:00"));

    assertProblem(
        book(second, roomId, tomorrow, "10:00", "11:00"), HttpStatus.CONFLICT, "SLOT_TAKEN");

    assertThat(rows.status(noShow)).isEqualTo("NO_SHOW");
    assertThat(mine(first).get(0).path("status").asText()).isEqualTo("PROMOTED");
  }

  // Expiry and leaving

  @Test
  void anEntryExpiresWhenItsSlotStartsAndCannotBeLeft() {
    var start = Instant.now().minus(Duration.ofMinutes(5));
    var entry = insertEntry(first, start, start.plus(Duration.ofHours(1)));

    var listed = mine(first).get(0);

    assertThat(listed.path("status").asText()).isEqualTo("EXPIRED");
    assertThat(listed.path("position").isNull()).isTrue();
    assertThat(leave(first, entry).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void anExpiredEntryDoesNotCountTowardTheLimitOrThePosition() {
    var start = Instant.now().minus(Duration.ofMinutes(5));
    for (var i = 0; i < 3; i++) {
      insertEntry(first, start.plus(Duration.ofSeconds(i)), start.plus(Duration.ofHours(1)));
    }
    book(holder, roomId, tomorrow, "10:00", "11:00");

    assertThat(join(first, roomId, tomorrow, "10:00", "11:00").getBody().path("position").asInt())
        .isEqualTo(1);
  }

  @Test
  void waitingEntriesAreListedFirstAndSoonestFirst() {
    book(holder, roomId, tomorrow, "10:00", "11:00");
    book(holder, roomId, tomorrow, "14:00", "15:00");
    var start = Instant.now().minus(Duration.ofMinutes(5));
    insertEntry(first, start, start.plus(Duration.ofHours(1)));
    join(first, roomId, tomorrow, "14:00", "15:00");
    join(first, roomId, tomorrow, "10:00", "11:00");

    var listed = mine(first);

    assertThat(listed.stream().map(e -> e.path("status").asText()))
        .containsExactly("WAITING", "WAITING", "EXPIRED");
    assertThat(listed.get(0).path("startAt").asText()).isEqualTo(tomorrow + "T10:00:00+08:00");
  }

  @Test
  void leavingGivesUpThePlaceAndTheSlotGoesToTheNextPerson() {
    var booking = id(book(holder, roomId, tomorrow, "10:00", "11:00"));
    var entry = id(join(first, roomId, tomorrow, "10:00", "11:00"));
    join(second, roomId, tomorrow, "10:00", "11:00");

    assertThat(leave(first, entry).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    assertThat(mine(first).get(0).path("status").asText()).isEqualTo("CANCELLED");
    assertThat(mine(second).get(0).path("position").asInt()).isEqualTo(1);
    assertThat(leave(first, entry).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    cancel(holder, booking);
    assertThat(mine(first).get(0).path("status").asText()).isEqualTo("CANCELLED");
    assertThat(mine(second).get(0).path("status").asText()).isEqualTo("PROMOTED");
  }

  @Test
  void aStudentCanJoinAgainAfterLeaving() {
    book(holder, roomId, tomorrow, "10:00", "11:00");
    leave(first, id(join(first, roomId, tomorrow, "10:00", "11:00")));

    assertThat(join(first, roomId, tomorrow, "10:00", "11:00").getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void onlyTheOwnerCanLeaveAndOnlyWhileWaiting() {
    var booking = id(book(holder, roomId, tomorrow, "10:00", "11:00"));
    var entry = id(join(first, roomId, tomorrow, "10:00", "11:00"));

    assertThat(leave(second, entry).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(leave(first, UUID.randomUUID().toString()).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    cancel(holder, booking);
    assertThat(leave(first, entry).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  // Other changes that free or rule out time

  @Test
  void aBlackoutExpiresTheEntriesInsideItAndPromotesTheTimeItFreesOutsideIt() {
    book(holder, roomId, tomorrow, "10:00", "12:00");
    join(first, roomId, tomorrow, "10:00", "11:00");
    join(second, roomId, tomorrow, "11:00", "12:00");
    var blackout =
        Map.of(
            "roomId",
            roomId,
            "startAt",
            sgt(tomorrow, "10:00"),
            "endAt",
            sgt(tomorrow, "11:00"),
            "reason",
            "Repairs");
    var blackouts = "/api/v1/facilities/" + facilityId + "/blackouts";

    var preview = api.post(blackouts + "?dryRun=true", blackout, adminToken).getBody();
    assertThat(preview.path("affectedWaitlistEntries").asInt()).isEqualTo(1);
    assertThat(mine(first).get(0).path("status").asText()).isEqualTo("WAITING");

    var created = api.post(blackouts, blackout, adminToken);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    assertThat(mine(first).get(0).path("status").asText()).isEqualTo("EXPIRED");
    assertThat(mine(second).get(0).path("status").asText()).isEqualTo("PROMOTED");
  }

  @Test
  void shorterOpeningHoursExpireTheEntriesThatNoLongerFit() {
    book(holder, roomId, tomorrow, "20:00", "21:00");
    book(holder, roomId, tomorrow, "10:00", "11:00");
    join(first, roomId, tomorrow, "20:00", "21:00");
    join(second, roomId, tomorrow, "10:00", "11:00");
    var path = "/api/v1/facilities/" + facilityId + "/opening-hours";

    var preview = api.put(path + "?dryRun=true", hours("18:00"), adminToken).getBody();
    assertThat(preview.path("affectedWaitlistEntries").asInt()).isEqualTo(1);
    assertThat(mine(first).get(0).path("status").asText()).isEqualTo("WAITING");

    api.put(path, hours("18:00"), adminToken);

    assertThat(mine(first).get(0).path("status").asText()).isEqualTo("EXPIRED");
    assertThat(mine(second).get(0).path("status").asText()).isEqualTo("WAITING");
  }

  @Test
  void cancellingARepeatingBookingPromotesTheQueue() {
    var series =
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
                    2,
                    "attendees",
                    1),
                holder.token())
            .getBody();
    join(first, roomId, tomorrow.plusWeeks(1), "10:00", "11:00");

    var cancelled =
        api.post(
            "/api/v1/booking-series/" + series.path("id").asText() + "/cancel",
            Map.of("fromDate", tomorrow.toString()),
            holder.token());

    assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(mine(first).get(0).path("status").asText()).isEqualTo("PROMOTED");
  }

  @Test
  void disablingAnAccountCancelsItsEntriesAndPromotesIntoItsBookings() {
    book(holder, roomId, tomorrow, "10:00", "11:00");
    book(holder, roomId, tomorrow, "14:00", "15:00");
    join(first, roomId, tomorrow, "10:00", "11:00");
    var entry = id(join(second, roomId, tomorrow, "14:00", "15:00"));

    api.patch("/api/v1/admin/users/" + second.id(), Map.of("status", "DISABLED"), adminToken);
    api.patch("/api/v1/admin/users/" + holder.id(), Map.of("status", "DISABLED"), adminToken);

    assertThat(mine(first).get(0).path("status").asText()).isEqualTo("PROMOTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM waitlist_entry WHERE id = ?::uuid", String.class, entry))
        .isEqualTo("CANCELLED");
    // The disabled student is not given the slot that became free.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM booking WHERE user_id = ?::uuid", Integer.class, second.id()))
        .isZero();
  }

  private String createRoom(String name) {
    return id(
        api.post(
            "/api/v1/facilities/" + facilityId + "/rooms",
            Map.of("name", name, "type", "STUDY_ROOM", "capacity", 6),
            adminToken));
  }

  private static List<Map<String, String>> hours(String closesAt) {
    var week = new ArrayList<Map<String, String>>();
    for (var day : DayOfWeek.values()) {
      week.add(Map.of("dayOfWeek", day.name(), "opensAt", "08:00", "closesAt", closesAt));
    }
    return week;
  }

  private ResponseEntity<JsonNode> book(
      TestUser who, String room, LocalDate date, String start, String end) {
    return api.post(
        "/api/v1/bookings",
        Map.of(
            "roomId", room, "startAt", sgt(date, start), "endAt", sgt(date, end), "attendees", 1),
        who.token());
  }

  private ResponseEntity<JsonNode> join(
      TestUser who, String room, LocalDate date, String start, String end) {
    return api.post(
        "/api/v1/waitlist",
        Map.of("roomId", room, "startAt", sgt(date, start), "endAt", sgt(date, end)),
        who.token());
  }

  private ResponseEntity<JsonNode> leave(TestUser who, String entryId) {
    return api.exchange(HttpMethod.DELETE, "/api/v1/waitlist/" + entryId, null, who.token());
  }

  private ResponseEntity<JsonNode> cancel(TestUser who, String bookingId) {
    return api.post("/api/v1/bookings/" + bookingId + "/cancel", Map.of(), who.token());
  }

  private List<JsonNode> mine(TestUser who) {
    var list = new ArrayList<JsonNode>();
    api.get("/api/v1/waitlist/mine", who.token()).getBody().forEach(list::add);
    return list;
  }

  /** Three no-shows last week, in a room nobody else in the test uses at that time. */
  private void recordNoShows(TestUser who, int count) {
    var rows = new BookingRows(jdbc);
    for (var i = 1; i <= count; i++) {
      var start = Instant.now().minus(Duration.ofDays(i));
      rows.recordedNoShow(otherRoomId, who.id(), start, start.plus(Duration.ofMinutes(30)));
    }
  }

  /** An entry written straight to the database, for states the API cannot create on purpose. */
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

  private static void assertProblem(
      ResponseEntity<JsonNode> response, HttpStatus status, String code) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getBody().path("code").asText()).isEqualTo(code);
  }

  private static String id(ResponseEntity<JsonNode> response) {
    return response.getBody().path("id").asText();
  }

  private static String sgt(LocalDate date, String time) {
    return date.atTime(LocalTime.parse(time)).atZone(SGT).toOffsetDateTime().toString();
  }

  private static Instant sgtInstant(LocalDate date, String time) {
    return date.atTime(LocalTime.parse(time)).atZone(SGT).toInstant();
  }
}
