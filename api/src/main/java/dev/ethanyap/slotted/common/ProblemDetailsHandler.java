package dev.ethanyap.slotted.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every exception that reaches a controller into one problem response shape. */
@RestControllerAdvice
public class ProblemDetailsHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);

  @ExceptionHandler(ApiException.class)
  ProblemDetail handleApi(ApiException e, HttpServletRequest request) {
    return Problems.of(e.code(), e.getMessage(), request.getRequestURI());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail handleInvalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
    var errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> new Problems.FieldError(f.getField(), f.getDefaultMessage()))
            .toList();
    return Problems.validation(errors, request.getRequestURI());
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    HandlerMethodValidationException.class
  })
  ProblemDetail handleMalformed(Exception e, HttpServletRequest request) {
    return Problems.of(
        ErrorCode.VALIDATION_FAILED,
        "The request is malformed or has invalid values.",
        request.getRequestURI());
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ProblemDetail handleNotFound(NoResourceFoundException e, HttpServletRequest request) {
    return Problems.of(
        ErrorCode.NOT_FOUND, "There is nothing at this address.", request.getRequestURI());
  }

  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail handleDenied(AccessDeniedException e, HttpServletRequest request) {
    return Problems.of(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.title(), request.getRequestURI());
  }

  @ExceptionHandler(AuthenticationException.class)
  ProblemDetail handleUnauthenticated(AuthenticationException e, HttpServletRequest request) {
    return Problems.of(
        ErrorCode.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED.title(), request.getRequestURI());
  }

  /** Other Spring MVC errors (wrong method, unsupported media type) keep their HTTP status. */
  @ExceptionHandler({
    HttpRequestMethodNotSupportedException.class,
    HttpMediaTypeException.class,
    ErrorResponseException.class
  })
  ProblemDetail handleOtherClientErrors(Exception e, HttpServletRequest request) {
    var error = (ErrorResponse) e;
    var problem =
        Problems.of(
            ErrorCode.VALIDATION_FAILED, error.getBody().getDetail(), request.getRequestURI());
    problem.setStatus(error.getStatusCode().value());
    return problem;
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
    log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
    return Problems.of(
        ErrorCode.INTERNAL_ERROR,
        "Something went wrong on our side. Try again, and quote the request ID if it keeps happening.",
        request.getRequestURI());
  }
}
