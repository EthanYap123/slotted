package dev.ethanyap.slotted;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the whole application on a random port against Postgres in Docker. Every integration test
 * uses the same settings, so Spring starts one context and one container for all of them. Tests
 * share the database, so each test creates its own data (for example a unique email).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "slotted.auth.jwt-secret=integration-test-signing-key-not-a-real-secret",
      "slotted.cors.allowed-origins=http://localhost:5173",
      "slotted.bootstrap-admin.email=" + IntegrationTest.ADMIN_EMAIL,
      "slotted.bootstrap-admin.password=" + IntegrationTest.ADMIN_PASSWORD
    })
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
public @interface IntegrationTest {

  /** The admin created on startup in every integration test run. */
  String ADMIN_EMAIL = "first.admin@example.com";

  String ADMIN_PASSWORD = "integration test admin password";
}
