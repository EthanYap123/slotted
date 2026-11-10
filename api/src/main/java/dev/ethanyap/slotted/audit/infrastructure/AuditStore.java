package dev.ethanyap.slotted.audit.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes {@code audit_log} with plain SQL. The JDBC template joins the transaction that
 * is already open, so a record is committed exactly when the change it describes is. There is no
 * update method: records are never changed (BR-33), and the table refuses updates anyway.
 */
@Repository
public class AuditStore {

  private static final String INSERT =
      """
      INSERT INTO audit_log
          (occurred_at, actor_id, action, entity_type, entity_id, facility_id, before, after,
           request_id)
      VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
      """;

  /** Names come from the users table, so a renamed user shows their current name. */
  private static final String SELECT =
      """
      SELECT a.id, a.occurred_at, a.actor_id, u.full_name AS actor_name, a.action, a.entity_type,
             a.entity_id, a.facility_id, a.before::text AS before, a.after::text AS after,
             a.request_id
        FROM audit_log a
        LEFT JOIN app_user u ON u.id = a.actor_id
      """;

  private final JdbcTemplate jdbc;

  AuditStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Adds records; {@code before} and {@code after} are JSON text or null. */
  public void insert(List<NewRow> rows) {
    jdbc.batchUpdate(
        INSERT,
        rows,
        rows.size(),
        (statement, row) -> {
          statement.setObject(1, utc(row.occurredAt()));
          statement.setObject(2, row.actorId());
          statement.setString(3, row.action());
          statement.setString(4, row.entityType());
          statement.setObject(5, row.entityId());
          statement.setObject(6, row.facilityId());
          statement.setString(7, row.before());
          statement.setString(8, row.after());
          statement.setString(9, row.requestId());
        });
  }

  /**
   * Records matching every filter that is set. Oldest first when looking at one thing, so its
   * history reads in order (AD-3); newest first otherwise.
   */
  public Page<StoredRow> search(Filter filter, Pageable pageable) {
    var where = new StringBuilder(" WHERE true");
    var args = new ArrayList<Object>();
    if (filter.entityType() != null) {
      where.append(" AND a.entity_type = ?");
      args.add(filter.entityType());
    }
    if (filter.entityId() != null) {
      where.append(" AND a.entity_id = ?");
      args.add(filter.entityId());
    }
    if (filter.actorId() != null) {
      where.append(" AND a.actor_id = ?");
      args.add(filter.actorId());
    }
    if (filter.from() != null) {
      where.append(" AND a.occurred_at >= ?");
      args.add(utc(filter.from()));
    }
    if (filter.to() != null) {
      where.append(" AND a.occurred_at < ?");
      args.add(utc(filter.to()));
    }
    var total =
        jdbc.queryForObject("SELECT count(*) FROM audit_log a" + where, Long.class, args.toArray());
    var order = filter.entityId() != null ? " ASC" : " DESC";
    var pageArgs = new ArrayList<>(args);
    pageArgs.add(pageable.getPageSize());
    pageArgs.add(pageable.getOffset());
    var rows =
        jdbc.query(
            SELECT
                + where
                + " ORDER BY a.occurred_at"
                + order
                + ", a.id"
                + order
                + " LIMIT ? OFFSET ?",
            AuditStore::row,
            pageArgs.toArray());
    return new PageImpl<>(rows, pageable, total == null ? 0 : total);
  }

  /** Deletes records older than {@code cutoff}; returns how many (BR-33). */
  public int deleteBefore(Instant cutoff) {
    return jdbc.update("DELETE FROM audit_log WHERE occurred_at < ?", utc(cutoff));
  }

  private static StoredRow row(ResultSet rs, int rowNumber) throws SQLException {
    return new StoredRow(
        rs.getLong("id"),
        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
        rs.getObject("actor_id", UUID.class),
        rs.getString("actor_name"),
        rs.getString("action"),
        rs.getString("entity_type"),
        rs.getObject("entity_id", UUID.class),
        rs.getObject("facility_id", UUID.class),
        rs.getString("before"),
        rs.getString("after"),
        rs.getString("request_id"));
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  /** A record to add. */
  public record NewRow(
      Instant occurredAt,
      UUID actorId,
      String action,
      String entityType,
      UUID entityId,
      UUID facilityId,
      String before,
      String after,
      String requestId) {}

  /** A stored record, with the actor's current name. */
  public record StoredRow(
      long id,
      Instant occurredAt,
      UUID actorId,
      String actorName,
      String action,
      String entityType,
      UUID entityId,
      UUID facilityId,
      String before,
      String after,
      String requestId) {}

  /**
   * Search filters; null means "any".
   *
   * @param to exclusive
   */
  public record Filter(String entityType, UUID entityId, UUID actorId, Instant from, Instant to) {}
}
