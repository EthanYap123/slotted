package dev.ethanyap.slotted.auth.infrastructure;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Web origins allowed to call the API from a browser ({@code slotted.cors.allowed-origins}). */
@Validated
@ConfigurationProperties("slotted.cors")
public record CorsProperties(@NotEmpty List<String> allowedOrigins) {}
