package dev.ethanyap.slotted.common;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 problem details in the one shape every error uses (docs/api/errors.md). */
public final class Problems {

  private static final String TYPE_BASE =
      "https://github.com/EthanYap123/slotted/blob/main/docs/api/errors.md#";

  private Problems() {}

  public static ProblemDetail of(ErrorCode code, String detail, String path) {
    var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status()), detail);
    problem.setType(URI.create(TYPE_BASE + code.name().toLowerCase(Locale.ROOT)));
    problem.setTitle(code.title());
    problem.setInstance(URI.create(path));
    problem.setProperty("code", code.name());
    var requestId = MDC.get(RequestIdFilter.MDC_KEY);
    if (requestId != null) {
      problem.setProperty("requestId", requestId);
    }
    return problem;
  }

  public static ProblemDetail validation(List<FieldError> errors, String path) {
    var problem = of(ErrorCode.VALIDATION_FAILED, "Check the highlighted fields.", path);
    problem.setProperty("errors", errors);
    return problem;
  }

  /** One invalid field, as listed in a {@code VALIDATION_FAILED} problem. */
  public record FieldError(String field, String message) {}
}
