package dev.ethanyap.slotted;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Starts the whole application against Postgres. Skipped on machines without Docker; CI has it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ApplicationIntegrationTest {

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;

  @Test
  void healthIsPublicAndUp() {
    var response = http.getForEntity("/actuator/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }

  @Test
  void readinessProbeIsUp() {
    var response = http.getForEntity("/actuator/health/readiness", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void otherEndpointsRequireAuthentication() {
    var response = http.getForEntity("/api/v1/me", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void migrationsEnableTheExtensionsTheSchemaNeeds() {
    var extensions =
        jdbc.queryForList(
            "SELECT extname FROM pg_extension WHERE extname IN ('btree_gist', 'citext')",
            String.class);

    assertThat(extensions).containsExactlyInAnyOrder("btree_gist", "citext");
  }

  @Test
  void clockRunsInUtc() {
    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
  }
}
