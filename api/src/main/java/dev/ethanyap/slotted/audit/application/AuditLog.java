package dev.ethanyap.slotted.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ethanyap.slotted.audit.domain.Change;
import dev.ethanyap.slotted.audit.infrastructure.AuditProperties;
import dev.ethanyap.slotted.audit.infrastructure.AuditStore;
import dev.ethanyap.slotted.audit.infrastructure.AuditStore.NewRow;
import dev.ethanyap.slotted.common.RequestIdFilter;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where every feature records its changes (PRD BR-32). Recording needs the change's own
 * transaction, so the change and its record are committed together or not at all; called without
 * one, it fails rather than write a record that might outlive a rolled-back change.
 */
@Component
public class AuditLog {

  private final AuditStore store;
  private final ObjectMapper json;
  private final AuditProperties properties;
  private final Clock clock;

  AuditLog(AuditStore store, ObjectMapper json, AuditProperties properties, Clock clock) {
    this.store = store;
    this.json = json;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(Change change) {
    recordAll(List.of(change));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recordAll(Collection<Change> changes) {
    if (changes.isEmpty()) {
      return;
    }
    var now = clock.instant();
    // Null outside a web request, for example in the sweep's own transactions.
    var requestId = MDC.get(RequestIdFilter.MDC_KEY);
    store.insert(
        changes.stream()
            .map(
                c ->
                    new NewRow(
                        now,
                        c.actorId(),
                        c.action().name(),
                        c.action().entity().name(),
                        c.entityId(),
                        c.facilityId(),
                        toJson(c.before()),
                        toJson(c.after()),
                        requestId))
            .toList());
  }

  /** Deletes records older than the retention period (BR-33); returns how many. */
  @Transactional
  public int purgeExpired() {
    return store.deleteBefore(clock.instant().minus(properties.retention()));
  }

  private String toJson(Map<String, ?> values) {
    if (values == null) {
      return null;
    }
    try {
      return json.writeValueAsString(values);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Audit values could not be written as JSON", e);
    }
  }
}
