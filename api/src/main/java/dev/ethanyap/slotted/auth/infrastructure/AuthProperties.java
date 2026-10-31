package dev.ethanyap.slotted.auth.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Token settings from {@code slotted.auth.*}. The app refuses to start without a JWT secret. */
@Validated
@ConfigurationProperties("slotted.auth")
public record AuthProperties(
    @NotBlank(message = "set JWT_SECRET") @Size(min = 32, message = "must be at least 32 characters") String jwtSecret,
    @NotBlank String issuer,
    @NotNull Duration accessTokenTtl,
    @NotNull Duration refreshTokenTtl) {}
