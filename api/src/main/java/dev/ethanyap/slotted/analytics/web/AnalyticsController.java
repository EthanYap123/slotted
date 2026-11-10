package dev.ethanyap.slotted.analytics.web;

import dev.ethanyap.slotted.analytics.application.UtilisationService;
import dev.ethanyap.slotted.analytics.domain.Utilisation.HeatCell;
import dev.ethanyap.slotted.analytics.domain.Utilisation.RoomUsage;
import dev.ethanyap.slotted.common.Principals;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /analytics/utilisation} in docs/api/openapi.yaml (PRD AD-4). The role on the token lets
 * managers and admins in; the service then checks the database for which facilities they may see.
 */
@RestController
@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
public class AnalyticsController {

  private final UtilisationService utilisation;

  public AnalyticsController(UtilisationService utilisation) {
    this.utilisation = utilisation;
  }

  @GetMapping("/api/v1/analytics/utilisation")
  public UtilisationResponse utilisation(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) UUID facilityId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
    var report = utilisation.report(Principals.userId(jwt), facilityId, fromDate, toDate);
    return new UtilisationResponse(fromDate, toDate, report.rooms(), report.heatmap());
  }

  /** Schema {@code UtilisationReport}. */
  public record UtilisationResponse(
      LocalDate fromDate, LocalDate toDate, List<RoomUsage> rooms, List<HeatCell> heatmap) {}
}
