package dev.ethanyap.slotted;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres for integration tests, the same major version as Neon (docs/ERD.md), and a fake
 * email sender.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
  }

  /** Records emails instead of sending them, so tests can read what would have gone out. */
  @Bean
  @Primary
  FakeEmailSender fakeEmailSender() {
    return new FakeEmailSender();
  }
}
