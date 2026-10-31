package dev.ethanyap.slotted.auth.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ethanyap.slotted.common.ErrorCode;
import dev.ethanyap.slotted.common.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Writes 401 and 403 responses from the security filters in the same problem shape as controller
 * errors, so the web app handles every error the same way.
 */
@Component
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  public ProblemSecurityHandlers(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
      throws IOException {
    write(request, response, ErrorCode.UNAUTHENTICATED);
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
      throws IOException {
    write(request, response, ErrorCode.FORBIDDEN);
  }

  private void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code)
      throws IOException {
    var problem = Problems.of(code, code.title(), request.getRequestURI());
    response.setStatus(code.status());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}
