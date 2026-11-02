package dev.ethanyap.slotted.user.application;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * When users' no-show suspensions end (BR-18). Derived from bookings, so implemented by the booking
 * feature; users without a suspension are left out of the result.
 */
public interface Suspensions {

  Map<UUID, Instant> suspendedUntil(Collection<UUID> userIds);
}
