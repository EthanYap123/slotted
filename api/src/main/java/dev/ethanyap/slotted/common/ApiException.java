package dev.ethanyap.slotted.common;

/** A failure the client caused or can act on, rendered as a problem response with its code. */
public class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ErrorCode code;

  public ApiException(ErrorCode code, String detail) {
    super(detail);
    this.code = code;
  }

  public ApiException(ErrorCode code) {
    this(code, code.title());
  }

  public ErrorCode code() {
    return code;
  }
}
