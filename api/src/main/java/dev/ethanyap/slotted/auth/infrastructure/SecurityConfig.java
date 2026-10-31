package dev.ethanyap.slotted.auth.infrastructure;

import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless JWT security (ADR-0002). Tokens come in the Authorization header, never in cookies, so
 * CSRF protection is not needed. Coarse role rules live here; finer checks use method security.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

  /** Name of the JWT claim that holds the user's role. */
  public static final String ROLE_CLAIM = "role";

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemSecurityHandlers problems)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/logout")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                    .authenticationEntryPoint(problems)
                    .accessDeniedHandler(problems))
        .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
        .build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(properties.allowedOrigins());
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", "X-Request-Id"));
    config.setExposedHeaders(List.of("X-Request-Id", "Location", "Retry-After", "ETag"));
    config.setAllowCredentials(false);
    config.setMaxAge(Duration.ofHours(1));
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  /** Maps the {@code role} claim to a {@code ROLE_*} authority for {@code hasRole} checks. */
  private static JwtAuthenticationConverter jwtAuthenticationConverter() {
    var authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName(ROLE_CLAIM);
    authorities.setAuthorityPrefix("ROLE_");
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }
}
