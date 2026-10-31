package dev.ethanyap.slotted.auth.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Password rules from ADR-0002, following NIST SP 800-63B: a minimum length and a check against
 * common passwords, and no rules about character classes.
 */
public final class PasswordPolicy {

  public static final int MIN_LENGTH = 10;
  public static final int MAX_LENGTH = 128;

  private final Set<String> commonPasswords;

  /**
   * @param commonPasswords lower-cased passwords to reject
   */
  public PasswordPolicy(Set<String> commonPasswords) {
    this.commonPasswords = Set.copyOf(commonPasswords);
  }

  /** Returns why the password is not acceptable, or empty if it is. */
  public Optional<String> problemWith(String password) {
    var length = password.codePointCount(0, password.length());
    if (length < MIN_LENGTH) {
      return Optional.of("Use at least " + MIN_LENGTH + " characters.");
    }
    if (length > MAX_LENGTH) {
      return Optional.of("Use at most " + MAX_LENGTH + " characters.");
    }
    if (commonPasswords.contains(password.toLowerCase(Locale.ROOT))) {
      return Optional.of("This password is too common. Try a longer phrase that is unique to you.");
    }
    return Optional.empty();
  }
}
