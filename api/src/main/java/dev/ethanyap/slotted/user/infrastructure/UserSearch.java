package dev.ethanyap.slotted.user.infrastructure;

import dev.ethanyap.slotted.user.domain.Role;
import dev.ethanyap.slotted.user.domain.User;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Filters for the admin user list. Each filter is skipped when its value is null. */
public final class UserSearch {

  private static final char ESCAPE = '\\';

  private UserSearch() {}

  public static Specification<User> matching(String query, Role role) {
    return Specification.allOf(textContains(query), hasRole(role));
  }

  /** Email or name contains the text, ignoring case; LIKE wildcards in the text match literally. */
  static Specification<User> textContains(String query) {
    if (query == null || query.isBlank()) {
      return null;
    }
    var pattern = "%" + escapeLike(query.strip().toLowerCase(Locale.ROOT)) + "%";
    return (user, q, cb) ->
        cb.or(
            cb.like(user.get("email"), pattern, ESCAPE),
            cb.like(cb.lower(user.get("fullName")), pattern, ESCAPE));
  }

  static Specification<User> hasRole(Role role) {
    return role == null ? null : (user, q, cb) -> cb.equal(user.get("role"), role);
  }

  static String escapeLike(String text) {
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
