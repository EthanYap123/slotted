package dev.ethanyap.slotted.audit.application;

import dev.ethanyap.slotted.audit.infrastructure.AuditStore;
import dev.ethanyap.slotted.audit.infrastructure.AuditStore.Filter;
import dev.ethanyap.slotted.audit.infrastructure.AuditStore.StoredRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Searching the audit log (PRD AD-3). Access is checked by callers: admins only. */
@Service
public class AuditSearch {

  private final AuditStore store;

  AuditSearch(AuditStore store) {
    this.store = store;
  }

  @Transactional(readOnly = true)
  public Page<StoredRow> search(Filter filter, Pageable pageable) {
    return store.search(filter, pageable);
  }
}
