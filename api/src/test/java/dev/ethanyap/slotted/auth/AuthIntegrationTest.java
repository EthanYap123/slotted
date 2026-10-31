package dev.ethanyap.slotted.auth;

import static dev.ethanyap.slotted.Api.GOOD_PASSWORD;
import static dev.ethanyap.slotted.Api.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ethanyap.slotted.Api;
import dev.ethanyap.slotted.IntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
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

/** PRD ST-1 and ADR-0002, end to end against Postgres. */
@IntegrationTest
class AuthIntegrationTest {

  @Autowired TestRestTemplate http;
  Api api;

  @BeforeEach
  void setUp() {
    api = new Api(http);
  }

  @Test
  void registeringCreatesAStudentAndSignsThemIn() {
    var email = uniqueEmail();

    var response = register("  " + email.toUpperCase(Locale.ROOT) + " ", GOOD_PASSWORD, "Ada Tan");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    var body = response.getBody();
    assertThat(body.path("accessToken").asText()).isNotBlank();
    assertThat(body.path("refreshToken").asText()).hasSizeGreaterThanOrEqualTo(43);
    assertThat(body.path("user").path("email").asText()).isEqualTo(email);
    assertThat(body.path("user").path("fullName").asText()).isEqualTo("Ada Tan");
    assertThat(body.path("user").path("role").asText()).isEqualTo("STUDENT");
    assertThat(body.path("user").path("status").asText()).isEqualTo("ACTIVE");
    assertThat(body.path("user").has("passwordHash")).isFalse();

    var expiresAt = OffsetDateTime.parse(body.path("accessTokenExpiresAt").asText());
    assertThat(expiresAt.getOffset().getTotalSeconds()).isEqualTo(8 * 3600);
    assertThat(Duration.between(OffsetDateTime.now(), expiresAt))
        .isBetween(Duration.ofMinutes(14), Duration.ofMinutes(15));
  }

  @Test
  void anEmailCanOnlyBeRegisteredOnceWhateverItsCase() {
    var email = uniqueEmail();
    register(email, GOOD_PASSWORD, "First");

    var response = register(email.toUpperCase(Locale.ROOT), GOOD_PASSWORD, "Second");

    assertProblem(response, HttpStatus.CONFLICT, "EMAIL_TAKEN");
  }

  @Test
  void weakPasswordsAreRejected() {
    assertProblem(register(uniqueEmail(), "short", "Ada"), HttpStatus.BAD_REQUEST, "WEAK_PASSWORD");
    assertProblem(
        register(uniqueEmail(), "Password123", "Ada"), HttpStatus.BAD_REQUEST, "WEAK_PASSWORD");
  }

  @Test
  void invalidFieldsAreListed() {
    var response = register("not-an-email", GOOD_PASSWORD, "");

    assertProblem(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    var fields = response.getBody().path("errors").findValuesAsText("field");
    assertThat(fields).containsExactlyInAnyOrder("email", "fullName");
  }

  @Test
  void signingInWorksWithAnyEmailCase() {
    var email = uniqueEmail();
    register(email, GOOD_PASSWORD, "Ada");

    var response = login(email.toUpperCase(Locale.ROOT), GOOD_PASSWORD);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().path("user").path("email").asText()).isEqualTo(email);
  }

  @Test
  void wrongPasswordAndUnknownEmailGiveTheSameError() {
    var email = uniqueEmail();
    register(email, GOOD_PASSWORD, "Ada");

    var wrongPassword = login(email, "not the right password");
    var unknownEmail = login(uniqueEmail(), GOOD_PASSWORD);

    assertProblem(wrongPassword, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
    assertProblem(unknownEmail, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
    assertThat(wrongPassword.getBody().path("detail"))
        .isEqualTo(unknownEmail.getBody().path("detail"));
  }

  @Test
  void meReturnsTheSignedInUser() {
    var signedIn = api.registerStudent();

    var response = api.get("/api/v1/me", signedIn.path("accessToken").asText());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().path("id")).isEqualTo(signedIn.path("user").path("id"));
    assertThat(response.getBody().path("managedFacilityIds").isArray()).isTrue();
  }

  @Test
  void requestsWithoutAValidTokenGetAProblemResponse() {
    var missing = api.get("/api/v1/me", null);
    var forged = api.get("/api/v1/me", "not.a.jwt");

    assertProblem(missing, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    assertProblem(forged, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    assertThat(missing.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(missing.getBody().path("requestId").asText())
        .isEqualTo(missing.getHeaders().getFirst("X-Request-Id"));
  }

  @Test
  void refreshingRotatesTheRefreshToken() {
    var first = api.registerStudent().path("refreshToken").asText();

    var refreshed = refresh(first);

    assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
    var second = refreshed.getBody().path("refreshToken").asText();
    assertThat(second).isNotEqualTo(first);
    assertThat(
            api.get("/api/v1/me", refreshed.getBody().path("accessToken").asText()).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void reusingAReplacedRefreshTokenEndsTheWholeSession() {
    var first = api.registerStudent().path("refreshToken").asText();
    var second = refresh(first).getBody().path("refreshToken").asText();

    var replay = refresh(first);

    assertProblem(replay, HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
    assertProblem(refresh(second), HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
  }

  @Test
  void signingOutRevokesTheRefreshToken() {
    var token = api.registerStudent().path("refreshToken").asText();

    var logout = api.post("/api/v1/auth/logout", Map.of("refreshToken", token));

    assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertProblem(refresh(token), HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
  }

  @Test
  void unknownRefreshTokensAreRejected() {
    assertProblem(
        refresh("this-token-was-never-issued"), HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
  }

  @Test
  void aRequestIdFromTheClientIsKept() {
    var headers = new HttpHeaders();
    headers.set("X-Request-Id", "trace-12345678");

    var response =
        http.exchange("/actuator/health", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("trace-12345678");
  }

  @Test
  void corsAllowsOnlyTheConfiguredWebOrigin() {
    var allowed = preflight("http://localhost:5173");
    var other = preflight("https://evil.example");

    assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(allowed.getHeaders().getAccessControlAllowOrigin())
        .isEqualTo("http://localhost:5173");
    assertThat(other.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private ResponseEntity<JsonNode> register(String email, String password, String fullName) {
    return api.post(
        "/api/v1/auth/register",
        Map.of("email", email, "password", password, "fullName", fullName));
  }

  private ResponseEntity<JsonNode> login(String email, String password) {
    return api.post("/api/v1/auth/login", Map.of("email", email, "password", password));
  }

  private ResponseEntity<JsonNode> refresh(String refreshToken) {
    return api.post("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken));
  }

  private ResponseEntity<String> preflight(String origin) {
    var headers = new HttpHeaders();
    headers.setOrigin(origin);
    headers.setAccessControlRequestMethod(HttpMethod.POST);
    headers.set("Access-Control-Request-Headers", "content-type");
    return http.exchange(
        "/api/v1/auth/login", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
  }

  private static void assertProblem(
      ResponseEntity<JsonNode> response, HttpStatus status, String code) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getBody().path("code").asText()).isEqualTo(code);
    assertThat(response.getBody().path("status").asInt()).isEqualTo(status.value());
  }
}
