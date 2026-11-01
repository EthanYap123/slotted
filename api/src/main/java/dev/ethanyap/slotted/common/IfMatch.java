package dev.ethanyap.slotted.common;

/**
 * Optimistic locking for edits: the client sends the {@code version} it last read in an {@code
 * If-Match} header, and the edit is refused with 412 if someone changed the record since.
 */
public final class IfMatch {

  private IfMatch() {}

  /**
   * Parses {@code 3}, {@code "3"} or {@code W/"3"} into a version, or returns null when the header
   * is absent.
   */
  public static Long parse(String header) {
    if (header == null || header.isBlank()) {
      return null;
    }
    var value = header.strip();
    if (value.startsWith("W/")) {
      value = value.substring(2);
    }
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new ApiException(
          ErrorCode.VALIDATION_FAILED, "If-Match must be the version number you last read.");
    }
  }

  /** Throws 412 when an expected version was given and does not match the current one. */
  public static void check(Long expected, long current) {
    if (expected != null && expected != current) {
      throw new ApiException(
          ErrorCode.VERSION_CONFLICT,
          "This was changed by someone else since you loaded it. Reload and try again.");
    }
  }
}
