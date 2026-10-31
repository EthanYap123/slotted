package dev.ethanyap.slotted.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

  private final PasswordPolicy policy = new PasswordPolicy(Set.of("password123", "qwertyuiop"));

  @Test
  void tenCharactersIsEnough() {
    assertThat(policy.problemWith("a1b2c3d4e5")).isEmpty();
  }

  @Test
  void nineCharactersIsTooShort() {
    assertThat(policy.problemWith("a1b2c3d4e")).contains("Use at least 10 characters.");
  }

  @Test
  void lengthCountsCharactersNotUtf16Units() {
    // Nine emoji are 18 UTF-16 units but only 9 characters.
    assertThat(policy.problemWith("😀".repeat(9))).isPresent();
    assertThat(policy.problemWith("😀".repeat(10))).isEmpty();
  }

  @Test
  void moreThan128CharactersIsTooLong() {
    assertThat(policy.problemWith("a".repeat(128))).isEmpty();
    assertThat(policy.problemWith("a".repeat(129))).contains("Use at most 128 characters.");
  }

  @ParameterizedTest
  @ValueSource(strings = {"password123", "PASSWORD123", "QwertyUiop"})
  void commonPasswordsAreRejectedWhateverTheirCase(String password) {
    assertThat(policy.problemWith(password)).get().asString().contains("too common");
  }

  @Test
  void noCharacterClassRules() {
    assertThat(policy.problemWith("all lower case words")).isEmpty();
  }
}
