package dev.ethanyap.slotted.common;

/**
 * Stable error codes returned in the {@code code} field of every problem response. Clients switch
 * on these, never on titles. Documented in docs/api/errors.md; keep both in step.
 */
public enum ErrorCode {
  VALIDATION_FAILED(400, "The request is not valid"),
  UNAUTHENTICATED(401, "Sign in to continue"),
  INVALID_CREDENTIALS(401, "Email or password is incorrect"),
  REFRESH_TOKEN_INVALID(401, "Your session has expired"),
  FORBIDDEN(403, "You are not allowed to do this"),
  NOT_FOUND(404, "Not found"),
  EMAIL_TAKEN(409, "An account with this email already exists"),
  WEAK_PASSWORD(400, "Choose a stronger password"),
  VERSION_CONFLICT(412, "This was changed by someone else"),
  RATE_LIMITED(429, "Too many requests"),
  LAST_ADMIN(422, "You cannot remove your own admin access"),
  INTERNAL_ERROR(500, "Something went wrong");

  private final int status;
  private final String title;

  ErrorCode(int status, String title) {
    this.status = status;
    this.title = title;
  }

  public int status() {
    return status;
  }

  public String title() {
    return title;
  }
}
