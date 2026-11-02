package dev.ethanyap.slotted.booking.infrastructure;

import dev.ethanyap.slotted.booking.domain.BookingPolicy;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * {@code slotted.booking.*}: the configurable booking rules, turned into a {@link BookingPolicy}.
 */
@Validated
@ConfigurationProperties("slotted.booking")
public record BookingProperties(
    @NotNull Duration window,
    @Min(1) int maxUpcoming,
    @Min(30) int maxDailyMinutes,
    @NotNull Duration lateCancellation,
    @NotNull Duration checkInOpensBefore,
    @NotNull Duration noShowGrace,
    @Min(1) int noShowLimit,
    @NotNull Duration noShowPeriod,
    @NotNull Duration suspension) {

  /** Registers the properties and exposes the policy as a bean. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(BookingProperties.class)
  static class PolicyConfig {

    @Bean
    BookingPolicy bookingPolicy(BookingProperties p) {
      return new BookingPolicy(
          p.window(),
          p.maxUpcoming(),
          p.maxDailyMinutes(),
          p.lateCancellation(),
          p.checkInOpensBefore(),
          p.noShowGrace(),
          p.noShowLimit(),
          p.noShowPeriod(),
          p.suspension());
    }
  }
}
