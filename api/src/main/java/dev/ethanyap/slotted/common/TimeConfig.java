package dev.ethanyap.slotted.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the clock every time-dependent rule uses, so tests can fix "now" (ADR-0003).
 * Domain code must take a {@link Clock} instead of calling {@code Instant.now()}.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
