package dev.ethanyap.slotted.facility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ethanyap.slotted.Api;
import dev.ethanyap.slotted.Api.TestUser;
import dev.ethanyap.slotted.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
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

/** PRD AD-2 and MG-2: facilities, managers and opening hours, with role and facility scope. */
@IntegrationTest
class FacilitiesIntegrationTest {

  @Autowired TestRestTemplate http;
  Api api;
  String adminToken;

  @BeforeEach
  void setUp() {
    api = new Api(http);
    adminToken = api.adminToken();
  }

  @Test
  void anAdminCreatesAFacilityThatEveryoneCanRead() {
    var name = "Library " + UUID.randomUUID();

    var created =
        api.post(
            "/api/v1/facilities",
            Map.of("name", "  " + name + " ", "description", "Quiet floor", "location", "Level 3"),
            adminToken);

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var id = created.getBody().path("id").asText();
    assertThat(created.getHeaders().getLocation()).hasPath("/api/v1/facilities/" + id);
    assertThat(created.getBody().path("name").asText()).isEqualTo(name);
    assertThat(created.getBody().path("timeZone").asText()).isEqualTo("Asia/Singapore");
    assertThat(created.getBody().path("openingHours")).isEmpty();

    var student = api.newStudent();
    var one = api.get("/api/v1/facilities/" + id, student.token());
    var all = api.get("/api/v1/facilities", student.token());
    assertThat(one.getBody().path("location").asText()).isEqualTo("Level 3");
    assertThat(all.getBody().findValuesAsText("id")).contains(id);
  }

  @Test
  void facilityNamesAreUniqueIgnoringCase() {
    var name = "Sports Hall " + UUID.randomUUID();
    api.post("/api/v1/facilities", Map.of("name", name), adminToken);

    var again = api.post("/api/v1/facilities", Map.of("name", name.toUpperCase()), adminToken);

    assertProblem(again, HttpStatus.CONFLICT, "NAME_TAKEN");
  }

  @Test
  void onlyAdminsCreateFacilitiesOrAssignManagers() {
    var facilityId = api.createFacility(adminToken);
    var manager = api.newManager(adminToken);
    var student = api.newStudent();

    for (var token : List.of(manager.token(), student.token())) {
      assertProblem(
          api.post("/api/v1/facilities", Map.of("name", "Not allowed"), token),
          HttpStatus.FORBIDDEN,
          "FORBIDDEN");
      assertProblem(
          api.put(managersPath(facilityId), Map.of("userIds", List.of(manager.id())), token),
          HttpStatus.FORBIDDEN,
          "FORBIDDEN");
    }
  }

  @Test
  void managersMustHaveTheManagerRoleAndShowUpOnTheirProfile() {
    var facilityId = api.createFacility(adminToken);
    var student = api.newStudent();
    var manager = api.newManager(adminToken);

    var notManager =
        api.put(managersPath(facilityId), Map.of("userIds", List.of(student.id())), adminToken);
    var unknown =
        api.put(
            managersPath(facilityId),
            Map.of("userIds", List.of(UUID.randomUUID().toString())),
            adminToken);
    api.assignManagers(facilityId, adminToken, manager.id());

    assertProblem(notManager, HttpStatus.UNPROCESSABLE_ENTITY, "NOT_A_MANAGER");
    assertProblem(unknown, HttpStatus.NOT_FOUND, "NOT_FOUND");
    var me = api.get("/api/v1/me", manager.token()).getBody();
    assertThat(texts(me.path("managedFacilityIds"))).containsExactly(facilityId);
  }

  @Test
  void aManagerCanEditOnlyTheirOwnFacility() {
    var mine = api.createFacility(adminToken);
    var other = api.createFacility(adminToken);
    var manager = managerOf(mine);

    var edited =
        api.patch(facilityPath(mine), Map.of("description", "Open to all years"), manager.token());
    var blocked =
        api.patch(facilityPath(other), Map.of("description", "Not mine"), manager.token());

    assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(edited.getBody().path("description").asText()).isEqualTo("Open to all years");
    assertProblem(blocked, HttpStatus.FORBIDDEN, "FORBIDDEN");
  }

  @Test
  void anEmptyStringClearsAnOptionalField() {
    var facilityId = api.createFacility(adminToken);

    var cleared = api.patch(facilityPath(facilityId), Map.of("location", ""), adminToken);

    assertThat(cleared.getBody().path("location").isNull()).isTrue();
  }

  @Test
  void anEditBasedOnAnOldVersionIsRefused() {
    var facilityId = api.createFacility(adminToken);
    var version = api.get(facilityPath(facilityId), adminToken).getBody().path("version").asLong();
    api.patch(facilityPath(facilityId), Map.of("description", "First edit"), adminToken);

    var stale = patchWithIfMatch(facilityPath(facilityId), "\"" + version + "\"");
    var current =
        patchWithIfMatch(
            facilityPath(facilityId),
            String.valueOf(
                api.get(facilityPath(facilityId), adminToken).getBody().path("version").asLong()));

    assertProblem(stale, HttpStatus.PRECONDITION_FAILED, "VERSION_CONFLICT");
    assertThat(current.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void openingHoursAreReplacedForTheWholeWeekAndReturnedInOrder() {
    var facilityId = api.createFacility(adminToken);
    var manager = managerOf(facilityId);

    var response =
        api.put(
            hoursPath(facilityId),
            List.of(
                hours("SATURDAY", "09:00", "17:00"),
                hours("MONDAY", "08:00", "22:00"),
                hours("TUESDAY", "08:30", "21:30")),
            manager.token());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().path("applied").asBoolean()).isTrue();
    var saved = api.get(facilityPath(facilityId), manager.token()).getBody().path("openingHours");
    assertThat(saved.findValuesAsText("dayOfWeek"))
        .containsExactly("MONDAY", "TUESDAY", "SATURDAY");
    assertThat(saved.get(1).path("opensAt").asText()).isEqualTo("08:30");
    assertThat(saved.get(1).path("closesAt").asText()).isEqualTo("21:30");
  }

  @Test
  void aDryRunChangesNothing() {
    var facilityId = api.createFacility(adminToken);

    var preview =
        api.put(
            hoursPath(facilityId) + "?dryRun=true",
            List.of(hours("MONDAY", "08:00", "12:00")),
            adminToken);

    assertThat(preview.getBody().path("applied").asBoolean()).isFalse();
    assertThat(preview.getBody().path("affectedBookings")).isEmpty();
    assertThat(api.get(facilityPath(facilityId), adminToken).getBody().path("openingHours"))
        .isEmpty();
  }

  @Test
  void invalidOpeningHoursAreRejected() {
    var facilityId = api.createFacility(adminToken);

    var closesFirst = putHours(facilityId, hours("MONDAY", "18:00", "09:00"));
    var quarterPast = putHours(facilityId, hours("MONDAY", "09:15", "18:00"));
    var dayTwice =
        putHours(facilityId, hours("MONDAY", "09:00", "12:00"), hours("MONDAY", "13:00", "18:00"));
    var notATime = putHours(facilityId, hours("MONDAY", "9am", "18:00"));
    var dryRunStillValidates =
        api.put(
            hoursPath(facilityId) + "?dryRun=true",
            List.of(hours("MONDAY", "09:00", "12:00"), hours("MONDAY", "13:00", "18:00")),
            adminToken);

    for (var response :
        List.of(closesFirst, quarterPast, dayTwice, notATime, dryRunStillValidates)) {
      assertProblem(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }
  }

  @Test
  void aStudentCannotChangeOpeningHours() {
    var facilityId = api.createFacility(adminToken);
    var student = api.newStudent();

    var response =
        api.put(hoursPath(facilityId), List.of(hours("MONDAY", "08:00", "12:00")), student.token());

    assertProblem(response, HttpStatus.FORBIDDEN, "FORBIDDEN");
  }

  @Test
  void unknownFacilitiesAreNotFound() {
    var response = api.get(facilityPath(UUID.randomUUID().toString()), adminToken);

    assertProblem(response, HttpStatus.NOT_FOUND, "NOT_FOUND");
  }

  private TestUser managerOf(String facilityId) {
    var manager = api.newManager(adminToken);
    api.assignManagers(facilityId, adminToken, manager.id());
    return manager;
  }

  private ResponseEntity<JsonNode> putHours(String facilityId, Map<?, ?>... days) {
    return api.put(hoursPath(facilityId), List.of(days), adminToken);
  }

  private ResponseEntity<JsonNode> patchWithIfMatch(String path, String ifMatch) {
    var headers = new HttpHeaders();
    headers.set("If-Match", ifMatch);
    return api.exchange(
        HttpMethod.PATCH, path, Map.of("description", "Second edit"), adminToken, headers);
  }

  private static Map<String, String> hours(String day, String opensAt, String closesAt) {
    return Map.of("dayOfWeek", day, "opensAt", opensAt, "closesAt", closesAt);
  }

  private static List<String> texts(JsonNode array) {
    var values = new ArrayList<String>();
    array.forEach(node -> values.add(node.asText()));
    return values;
  }

  private static String facilityPath(String facilityId) {
    return "/api/v1/facilities/" + facilityId;
  }

  private static String hoursPath(String facilityId) {
    return facilityPath(facilityId) + "/opening-hours";
  }

  private static String managersPath(String facilityId) {
    return facilityPath(facilityId) + "/managers";
  }

  private static void assertProblem(
      ResponseEntity<JsonNode> response, HttpStatus status, String code) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getBody().path("code").asText()).isEqualTo(code);
  }
}
