package dev.ethanyap.slotted.user;

import static dev.ethanyap.slotted.Api.GOOD_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ethanyap.slotted.Api;
import dev.ethanyap.slotted.IntegrationTest;
import dev.ethanyap.slotted.auth.application.AdminBootstrap;
import dev.ethanyap.slotted.user.domain.Role;
import dev.ethanyap.slotted.user.infrastructure.UserRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** PRD AD-1 (user part) and role checks on admin endpoints. */
@IntegrationTest
class AdminUsersIntegrationTest {

  @Autowired TestRestTemplate http;
  @Autowired UserRepository users;
  @Autowired AdminBootstrap bootstrap;
  Api api;
  String adminToken;

  @BeforeEach
  void setUp() {
    api = new Api(http);
    adminToken = login(IntegrationTest.ADMIN_EMAIL, IntegrationTest.ADMIN_PASSWORD);
  }

  @Test
  void theFirstAdminIsCreatedOnStartupOnlyOnce() {
    bootstrap.createFirstAdminIfMissing();

    var admins = users.findAll().stream().filter(u -> u.getRole() == Role.ADMIN).toList();
    assertThat(admins).extracting(u -> u.getEmail()).containsOnlyOnce(IntegrationTest.ADMIN_EMAIL);
  }

  @Test
  void studentsAndManagersCannotUseAdminEndpoints() {
    var student = api.registerStudent();
    var manager = api.registerStudent();
    promote(manager, "MANAGER");
    var managerToken = login(email(manager), GOOD_PASSWORD);

    for (var token : new String[] {accessToken(student), managerToken}) {
      assertProblem(api.get("/api/v1/admin/users", token), HttpStatus.FORBIDDEN, "FORBIDDEN");
      assertProblem(
          api.patch(adminUserPath(student), Map.of("role", "ADMIN"), token),
          HttpStatus.FORBIDDEN,
          "FORBIDDEN");
    }
  }

  @Test
  void adminsCanSearchByEmailOrNameAndFilterByRole() {
    var student = api.registerStudent();

    var byEmail = api.get("/api/v1/admin/users?q=" + email(student).substring(0, 20), adminToken);
    var byName = api.get("/api/v1/admin/users?q=TEST STUDENT&role=STUDENT&size=1", adminToken);
    var admins = api.get("/api/v1/admin/users?role=ADMIN", adminToken);

    assertThat(byEmail.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(byEmail.getBody().path("items").findValuesAsText("email"))
        .containsExactly(email(student));
    assertThat(byName.getBody().path("items")).hasSize(1);
    assertThat(byName.getBody().path("page").path("size").asInt()).isEqualTo(1);
    assertThat(byName.getBody().path("page").path("totalElements").asInt()).isPositive();
    assertThat(admins.getBody().path("items").findValuesAsText("role")).containsOnly("ADMIN");
  }

  @Test
  void likeWildcardsInTheSearchTextMatchLiterally() {
    // The URL template encodes "%" as %25, so the server receives a literal "%".
    var response = api.get("/api/v1/admin/users?q=%", adminToken);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().path("items")).isEmpty();
  }

  @Test
  void changingARoleEndsTheUsersSessionsAndAppliesAtNextSignIn() {
    var student = api.registerStudent();

    var updated = promote(student, "MANAGER");

    assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(updated.getBody().path("role").asText()).isEqualTo("MANAGER");
    assertProblem(refresh(student), HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
    var signedIn =
        api.post("/api/v1/auth/login", Map.of("email", email(student), "password", GOOD_PASSWORD));
    assertThat(signedIn.getBody().path("user").path("role").asText()).isEqualTo("MANAGER");
  }

  @Test
  void aDisabledUserCannotSignInOrRefreshUntilReEnabled() {
    var student = api.registerStudent();

    var disabled = api.patch(adminUserPath(student), Map.of("status", "DISABLED"), adminToken);

    assertThat(disabled.getBody().path("status").asText()).isEqualTo("DISABLED");
    assertProblem(refresh(student), HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
    assertProblem(
        api.post("/api/v1/auth/login", Map.of("email", email(student), "password", GOOD_PASSWORD)),
        HttpStatus.UNAUTHORIZED,
        "INVALID_CREDENTIALS");

    api.patch(adminUserPath(student), Map.of("status", "ACTIVE"), adminToken);

    assertThat(login(email(student), GOOD_PASSWORD)).isNotBlank();
  }

  @Test
  void anAdminCannotRemoveTheirOwnAccess() {
    var me = api.get("/api/v1/me", adminToken).getBody();
    var path = "/api/v1/admin/users/" + me.path("id").asText();

    assertProblem(
        api.patch(path, Map.of("role", "MANAGER"), adminToken),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "LAST_ADMIN");
    assertProblem(
        api.patch(path, Map.of("status", "DISABLED"), adminToken),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "LAST_ADMIN");
  }

  @Test
  void aDemotedAdminLosesAccessEvenWithAnUnexpiredToken() {
    var other = api.registerStudent();
    promote(other, "ADMIN");
    var otherAdminToken = login(email(other), GOOD_PASSWORD);

    promote(other, "STUDENT");

    // The token still says ADMIN, but the database no longer does.
    assertProblem(
        api.get("/api/v1/admin/users", otherAdminToken), HttpStatus.FORBIDDEN, "FORBIDDEN");
  }

  @Test
  void anEmptyChangeIsRejectedAndUnknownUsersAreNotFound() {
    var student = api.registerStudent();

    assertProblem(
        api.patch(adminUserPath(student), Map.of(), adminToken),
        HttpStatus.BAD_REQUEST,
        "VALIDATION_FAILED");
    assertProblem(
        api.patch(
            "/api/v1/admin/users/00000000-0000-0000-0000-000000000000",
            Map.of("role", "MANAGER"),
            adminToken),
        HttpStatus.NOT_FOUND,
        "NOT_FOUND");
  }

  @Test
  void liftingASuspensionIsAccepted() {
    var student = api.registerStudent();

    var response = api.patch(adminUserPath(student), Map.of("liftSuspension", true), adminToken);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private ResponseEntity<JsonNode> promote(JsonNode signedIn, String role) {
    return api.patch(adminUserPath(signedIn), Map.of("role", role), adminToken);
  }

  private ResponseEntity<JsonNode> refresh(JsonNode signedIn) {
    return api.post(
        "/api/v1/auth/refresh", Map.of("refreshToken", signedIn.path("refreshToken").asText()));
  }

  private String login(String email, String password) {
    var response = api.post("/api/v1/auth/login", Map.of("email", email, "password", password));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response.getBody().path("accessToken").asText();
  }

  private static String adminUserPath(JsonNode signedIn) {
    return "/api/v1/admin/users/" + signedIn.path("user").path("id").asText();
  }

  private static String email(JsonNode signedIn) {
    return signedIn.path("user").path("email").asText();
  }

  private static String accessToken(JsonNode signedIn) {
    return signedIn.path("accessToken").asText();
  }

  private static void assertProblem(
      ResponseEntity<JsonNode> response, HttpStatus status, String code) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getBody().path("code").asText()).isEqualTo(code);
  }
}
