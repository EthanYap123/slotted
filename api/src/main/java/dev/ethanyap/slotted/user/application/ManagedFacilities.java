package dev.ethanyap.slotted.user.application;

import java.util.List;
import java.util.UUID;

/**
 * Which facilities a user manages. Implemented by the facility feature, so users can show their
 * facilities without the user feature depending on facilities.
 */
public interface ManagedFacilities {

  List<UUID> facilityIdsManagedBy(UUID userId);
}
