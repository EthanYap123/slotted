package dev.ethanyap.slotted;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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

  public ResponseEntity<JsonNode> exchange(
      HttpMethod method, String path, Object body, String accessToken) {
    var headers = new HttpHeaders();
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
}
