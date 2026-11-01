package dev.ethanyap.slotted;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Small JSON client for integration tests, so tests read like the API contract. */
public final class Api {

  /** A password that passes the password policy. */
  public static final String GOOD_PASSWORD = "correct horse battery staple";

  private final TestRestTemplate http;

  public Api(TestRestTemplate http) {
    this.http = http;
  }

  public ResponseEntity<JsonNode> get(String path, String accessToken) {
    return exchange(HttpMethod.GET, path, null, accessToken);
  }

  public ResponseEntity<JsonNode> post(String path, Object body) {
    return exchange(HttpMethod.POST, path, body, null);
  }

  public ResponseEntity<JsonNode> post(String path, Object body, String accessToken) {
    return exchange(HttpMethod.POST, path, body, accessToken);
  }

  public ResponseEntity<JsonNode> patch(String path, Object body, String accessToken) {
    return exchange(HttpMethod.PATCH, path, body, accessToken);
  }

  public ResponseEntity<JsonNode> put(String path, Object body, String accessToken) {
    return exchange(HttpMethod.PUT, path, body, accessToken);
  }

  public ResponseEntity<JsonNode> exchange(
      HttpMethod method, String path, Object body, String accessToken) {
    return exchange(method, path, body, accessToken, new HttpHeaders());
  }

  public ResponseEntity<JsonNode> exchange(
      HttpMethod method, String path, Object body, String accessToken, HttpHeaders headers) {
    if (accessToken != null) {
      headers.setBearerAuth(accessToken);
    }
    return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
  }

  /** An email no other test uses, since tests share one database. */
  public static String uniqueEmail() {
    return "user-" + UUID.randomUUID() + "@example.com";
  }

  /** Registers a new student and returns the {@code AuthResponse}. */
  public JsonNode registerStudent() {
    var response =
        post(
            "/api/v1/auth/register",
            Map.of("email", uniqueEmail(), "password", GOOD_PASSWORD, "fullName", "Test Student"));
    if (!response.getStatusCode().is2xxSuccessful()) {
      throw new IllegalStateException("Registration failed: " + response.getBody());
    }
    return response.getBody();
  }

  /** Signs in and returns the access token. */
  public String signIn(String email, String password) {
    var response = post("/api/v1/auth/login", Map.of("email", email, "password", password));
    if (response.getStatusCode() != HttpStatus.OK) {
      throw new IllegalStateException("Sign-in failed: " + response.getBody());
    }
    return response.getBody().path("accessToken").asText();
  }

  /** Access token of the admin created on startup. */
  public String adminToken() {
    return signIn(IntegrationTest.ADMIN_EMAIL, IntegrationTest.ADMIN_PASSWORD);
  }

  /** A new user with the Manager role, signed in again so the token carries the new role. */
  public TestUser newManager(String adminToken) {
    var registered = registerStudent();
    var id = registered.path("user").path("id").asText();
    var email = registered.path("user").path("email").asText();
    var promoted = patch("/api/v1/admin/users/" + id, Map.of("role", "MANAGER"), adminToken);
    if (promoted.getStatusCode() != HttpStatus.OK) {
      throw new IllegalStateException("Promotion failed: " + promoted.getBody());
    }
    return new TestUser(id, email, signIn(email, GOOD_PASSWORD));
  }

  /** A new student. */
  public TestUser newStudent() {
    var registered = registerStudent();
    return new TestUser(
        registered.path("user").path("id").asText(),
        registered.path("user").path("email").asText(),
        registered.path("accessToken").asText());
  }

  /** Creates a facility with a unique name and returns its ID. */
  public String createFacility(String adminToken) {
    var response =
        post(
            "/api/v1/facilities",
            Map.of("name", "Facility " + UUID.randomUUID(), "location", "Block T"),
            adminToken);
    if (response.getStatusCode() != HttpStatus.CREATED) {
      throw new IllegalStateException("Facility creation failed: " + response.getBody());
    }
    return response.getBody().path("id").asText();
  }

  /** Makes the user one of the facility's managers. */
  public void assignManagers(String facilityId, String adminToken, String... userIds) {
    var response =
        put(
            "/api/v1/facilities/" + facilityId + "/managers",
            Map.of("userIds", List.of(userIds)),
            adminToken);
    if (response.getStatusCode() != HttpStatus.NO_CONTENT) {
      throw new IllegalStateException("Assigning managers failed: " + response.getBody());
    }
  }

  /** A user created for a test: ID, email and a current access token. */
  public record TestUser(String id, String email, String token) {}
}
