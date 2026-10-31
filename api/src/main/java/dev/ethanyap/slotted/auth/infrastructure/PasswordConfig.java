package dev.ethanyap.slotted.auth.infrastructure;

import dev.ethanyap.slotted.auth.domain.PasswordPolicy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class PasswordConfig {

  static final String COMMON_PASSWORDS = "security/common-passwords.txt";

  /** BCrypt by default, with the algorithm stored in the hash so it can change later. */
  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  PasswordPolicy passwordPolicy() {
    var resource = new ClassPathResource(COMMON_PASSWORDS);
    try (var reader =
        new BufferedReader(
            new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
      var passwords =
          reader
              .lines()
              .filter(line -> !line.isBlank() && !line.startsWith("#"))
              .collect(Collectors.toSet());
      return new PasswordPolicy(passwords);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + COMMON_PASSWORDS, e);
    }
  }
}
