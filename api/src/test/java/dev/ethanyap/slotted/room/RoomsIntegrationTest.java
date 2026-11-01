package dev.ethanyap.slotted.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ethanyap.slotted.Api;
import dev.ethanyap.slotted.Api.TestUser;
import dev.ethanyap.slotted.IntegrationTest;
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

/** PRD MG-1: managers add and edit rooms in their own facilities only. */
@IntegrationTest
class RoomsIntegrationTest {

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  Api api;
  String adminToken;
  String facilityId;
  TestUser manager;

  @BeforeEach
  void setUp() {
    api = new Api(http);
    adminToken = api.adminToken();
    facilityId = api.createFacility(adminToken);
    manager = api.newManager(adminToken);
    api.assignManagers(facilityId, adminToken, manager.id());
  }

  @Test
  void aManagerAddsARoomWithDefaultsAndAHiddenCheckInToken() {
    var created = createRoom(manager.token(), room("Study Room 1", "STUDY_ROOM", 6, null));

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var body = created.getBody();
    assertThat(created.getHeaders().getLocation())
        .hasPath("/api/v1/rooms/" + body.path("id").asText());
    assertThat(body.path("facilityId").asText()).isEqualTo(facilityId);
    assertThat(body.path("maxBookingMinutes").asInt()).isEqualTo(120);
    assertThat(body.path("active").asBoolean()).isTrue();
    assertThat(body.has("checkInToken")).isFalse();
    var token =
        jdbc.queryForObject(
            "SELECT checkin_token FROM room WHERE id = ?::uuid",
            String.class,
            body.path("id").asText());
    assertThat(token).hasSizeGreaterThanOrEqualTo(32);
  }

  @Test
  void managersCannotChangeRoomsInOtherFacilitiesAndStudentsCannotChangeAny() {
    var otherFacility = api.createFacility(adminToken);
    var otherRoomId =
        createRoomIn(otherFacility, adminToken, room("Court A", "SPORTS_COURT", 10, 60))
            .getBody()
            .path("id")
            .asText();
    var student = api.newStudent();

    assertProblem(
        createRoomIn(otherFacility, manager.token(), room("Mine?", "STUDY_ROOM", 4, null)),
        HttpStatus.FORBIDDEN,
        "FORBIDDEN");
    assertProblem(
        api.patch(roomPath(otherRoomId), Map.of("capacity", 2), manager.token()),
        HttpStatus.FORBIDDEN,
        "FORBIDDEN");
    assertProblem(
        createRoom(student.token(), room("Student room", "STUDY_ROOM", 4, null)),
        HttpStatus.FORBIDDEN,
        "FORBIDDEN");
    assertProblem(
        api.patch(roomPath(otherRoomId), Map.of("capacity", 2), student.token()),
        HttpStatus.FORBIDDEN,
        "FORBIDDEN");
  }

  @Test
  void aManagerWhoLosesTheRoleLosesAccessAtOnce() {
    api.patch("/api/v1/admin/users/" + manager.id(), Map.of("role", "STUDENT"), adminToken);

    // The access token still says MANAGER; the database decides.
    var response = createRoom(manager.token(), room("Too late", "STUDY_ROOM", 4, null));

    assertProblem(response, HttpStatus.FORBIDDEN, "FORBIDDEN");
  }

  @Test
  void roomNamesAreUniqueWithinAFacilityIgnoringCase() {
    createRoom(manager.token(), room("Meeting Room", "MEETING_ROOM", 8, null));

    var again = createRoom(manager.token(), room("meeting room", "MEETING_ROOM", 8, null));
    var otherFacility = api.createFacility(adminToken);
    var elsewhere =
        createRoomIn(otherFacility, adminToken, room("Meeting Room", "MEETING_ROOM", 8, null));

    assertProblem(again, HttpStatus.CONFLICT, "NAME_TAKEN");
    assertThat(elsewhere.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void invalidRoomsAreRejected() {
    var tooLong = createRoom(manager.token(), room("A", "STUDY_ROOM", 4, 270));
    var notAStep = createRoom(manager.token(), room("B", "STUDY_ROOM", 4, 45));
    var noCapacity = createRoom(manager.token(), room("C", "STUDY_ROOM", 0, null));
    var badType = createRoom(manager.token(), room("D", "BALLROOM", 4, null));

    for (var response : List.of(tooLong, notAStep, noCapacity, badType)) {
      assertProblem(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }
  }

  @Test
  void deactivatedRoomsAreHiddenFromStudentsButNotFromTheirManager() {
    var roomId =
        createRoom(manager.token(), room("Pod 1", "STUDY_ROOM", 2, 60))
            .getBody()
            .path("id")
            .asText();
    createRoom(manager.token(), room("Pod 2", "STUDY_ROOM", 2, 60));
    var student = api.newStudent();

    var deactivated = api.patch(roomPath(roomId), Map.of("active", false), manager.token());

    assertThat(deactivated.getBody().path("active").asBoolean()).isFalse();
    assertThat(names(listRooms("", student.token()))).containsExactly("Pod 2");
    assertThat(names(listRooms("?includeInactive=true", student.token()))).containsExactly("Pod 2");
    assertThat(names(listRooms("?includeInactive=true", manager.token())))
        .containsExactly("Pod 1", "Pod 2");
    assertThat(api.get(roomPath(roomId), student.token()).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void roomsCanBeFilteredByType() {
    createRoom(manager.token(), room("Court 1", "SPORTS_COURT", 10, 60));
    createRoom(manager.token(), room("Room 1", "STUDY_ROOM", 4, 120));

    var courts = listRooms("?type=SPORTS_COURT", manager.token());

    assertThat(names(courts)).containsExactly("Court 1");
  }

  @Test
  void aManagerEditsTheirRoom() {
    var roomId =
        createRoom(manager.token(), room("Lab", "MEETING_ROOM", 10, 120))
            .getBody()
            .path("id")
            .asText();

    var edited =
        api.patch(
            roomPath(roomId),
            Map.of("name", "Lab 2", "capacity", 12, "maxBookingMinutes", 90),
            manager.token());

    assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(edited.getBody().path("name").asText()).isEqualTo("Lab 2");
    assertThat(edited.getBody().path("capacity").asInt()).isEqualTo(12);
    assertThat(edited.getBody().path("maxBookingMinutes").asInt()).isEqualTo(90);
    assertThat(edited.getBody().path("version").asLong()).isPositive();
  }

  @Test
  void unknownRoomsAndFacilitiesAreNotFound() {
    var unknown = UUID.randomUUID().toString();

    assertProblem(api.get(roomPath(unknown), adminToken), HttpStatus.NOT_FOUND, "NOT_FOUND");
    assertProblem(
        api.patch(roomPath(unknown), Map.of("capacity", 3), adminToken),
        HttpStatus.NOT_FOUND,
        "NOT_FOUND");
    assertProblem(
        api.get("/api/v1/facilities/" + unknown + "/rooms", adminToken),
        HttpStatus.NOT_FOUND,
        "NOT_FOUND");
  }

  private ResponseEntity<JsonNode> createRoom(String token, Map<String, Object> body) {
    return createRoomIn(facilityId, token, body);
  }

  private ResponseEntity<JsonNode> createRoomIn(
      String facility, String token, Map<String, Object> body) {
    return api.post("/api/v1/facilities/" + facility + "/rooms", body, token);
  }

  private JsonNode listRooms(String query, String token) {
    return api.get("/api/v1/facilities/" + facilityId + "/rooms" + query, token).getBody();
  }

  private static Map<String, Object> room(
      String name, String type, int capacity, Integer maxBookingMinutes) {
    var body = new HashMap<String, Object>();
    body.put("name", name);
    body.put("type", type);
    body.put("capacity", capacity);
    if (maxBookingMinutes != null) {
      body.put("maxBookingMinutes", maxBookingMinutes);
    }
    return body;
  }

  private static List<String> names(JsonNode rooms) {
    return rooms.findValuesAsText("name");
  }

  private static String roomPath(String roomId) {
    return "/api/v1/rooms/" + roomId;
  }

  private static void assertProblem(
      ResponseEntity<JsonNode> response, HttpStatus status, String code) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getBody().path("code").asText()).isEqualTo(code);
  }
}
