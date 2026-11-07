package dev.ethanyap.slotted.common;

import java.util.Map;

/** A failure the client caused or can act on, rendered as a problem response with its code. */
public class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ErrorCode code;
  private final transient Map<String, Object> properties;

  public ApiException(ErrorCode code, String detail) {
    this(code, detail, Map.of());
  }

  /** A failure with extra fields for the problem response. */
  public ApiException(ErrorCode code, String detail, Map<String, Object> properties) {
    super(detail);
    this.code = code;
    this.properties = properties;
  }

  public ApiException(ErrorCode code) {
    this(code, code.title());
  }

  public ErrorCode code() {
    return code;
  }

  public Map<String, Object> properties() {
    return properties;
  }
}
