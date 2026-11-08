package dev.ethanyap.slotted.maintenance.web;

import dev.ethanyap.slotted.maintenance.application.SweepService;
import dev.ethanyap.slotted.maintenance.application.SweepService.SweepResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /internal/maintenance/sweep} in docs/api/openapi.yaml. Open to anyone with the key, not a
 * JWT: the caller is a scheduled GitHub Actions workflow.
 */
@RestController
public class MaintenanceController {

  private final SweepService sweeps;

  public MaintenanceController(SweepService sweeps) {
    this.sweeps = sweeps;
  }

  @PostMapping("/api/v1/internal/maintenance/sweep")
  public SweepResult sweep(
      @RequestHeader(name = "X-Maintenance-Key", required = false) String key) {
    return sweeps.sweep(key);
  }
}
