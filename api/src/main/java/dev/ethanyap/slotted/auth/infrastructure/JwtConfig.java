package dev.ethanyap.slotted.auth.infrastructure;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** HS256 access tokens signed and checked with one shared secret (ADR-0002). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  AuthProperties.class,
  CorsProperties.class,
  BootstrapAdminProperties.class
})
public class JwtConfig {

  /** Allowed difference between server clocks when checking expiry. */
  static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

  @Bean
  JwtEncoder jwtEncoder(AuthProperties properties) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
  }

  @Bean
  JwtDecoder jwtDecoder(AuthProperties properties, Clock clock) {
    return decoder(properties, clock);
  }

  /** Builds the decoder; separate so tests can check expiry with a different clock. */
  static JwtDecoder decoder(AuthProperties properties, Clock clock) {
    var decoder =
        NimbusJwtDecoder.withSecretKey(signingKey(properties))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    var timestamps = new JwtTimestampValidator(CLOCK_SKEW);
    timestamps.setClock(clock);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            timestamps, new JwtIssuerValidator(properties.issuer())));
    return decoder;
  }

  private static SecretKey signingKey(AuthProperties properties) {
    return new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }
}
