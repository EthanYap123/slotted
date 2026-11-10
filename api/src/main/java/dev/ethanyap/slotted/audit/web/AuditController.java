package dev.ethanyap.slotted.audit.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ethanyap.slotted.audit.application.AuditSearch;
import dev.ethanyap.slotted.audit.domain.AuditEntity;
import dev.ethanyap.slotted.audit.infrastructure.AuditStore.Filter;
import dev.ethanyap.slotted.audit.infrastructure.AuditStore.StoredRow;
import dev.ethanyap.slotted.common.ApiTime;
import dev.ethanyap.slotted.common.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /admin/audit-log} in docs/api/openapi.yaml (PRD AD-3). Admin only, checked twice: the URL
 * rule on the token's role, and here against the database, so an admin who was just demoted is
 * refused at once.
 */
@RestController
@PreAuthorize("@facilityAccess.isAdmin(authentication)")
public class AuditController {

  /** Shown as the actor of changes the system made itself. */
  static final String SYSTEM = "System";

  private final AuditSearch audit;
  private final ObjectMapper json;

  public AuditController(AuditSearch audit, ObjectMapper json) {
    this.audit = audit;
    this.json = json;
  }

  @GetMapping("/api/v1/admin/audit-log")
  public PageResponse<AuditRecordResponse> search(
      @RequestParam(required = false) AuditEntity entityType,
      @RequestParam(required = false) UUID entityId,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) OffsetDateTime from,
      @RequestParam(required = false) OffsetDateTime to,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    var filter =
        new Filter(
            entityType == null ? null : entityType.name(),
            entityId,
            actorId,
            from == null ? null : from.toInstant(),
            to == null ? null : to.toInstant());
    return PageResponse.of(audit.search(filter, PageRequest.of(page, size)), this::toResponse);
  }

  private AuditRecordResponse toResponse(StoredRow row) {
    return new AuditRecordResponse(
        row.id(),
        ApiTime.toApi(row.occurredAt()),
        row.actorId(),
        row.actorId() == null ? SYSTEM : row.actorName(),
        row.action(),
        row.entityType(),
        row.entityId(),
        parse(row.before()),
        parse(row.after()),
        row.requestId());
  }

  private JsonNode parse(String text) {
    if (text == null) {
      return null;
    }
    try {
      return json.readTree(text);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Stored audit values are not valid JSON", e);
    }
  }

  /** Schema {@code AuditRecord}. */
  public record AuditRecordResponse(
      long id,
      OffsetDateTime occurredAt,
      UUID actorId,
      String actorName,
      String action,
      String entityType,
      UUID entityId,
      JsonNode before,
      JsonNode after,
      String requestId) {}
}
