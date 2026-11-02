package dev.ethanyap.slotted.booking.application;

import dev.ethanyap.slotted.booking.domain.Booking;
import dev.ethanyap.slotted.booking.domain.EffectiveStatus;
import dev.ethanyap.slotted.facility.domain.Facility;
import dev.ethanyap.slotted.room.domain.Room;

/**
 * A booking with what is needed to show it: its room and facility, its status at the time of the
 * request, and the booker's name when the viewer may see it (staff only, per the PRD's privacy
 * rule).
 */
public record BookingView(
    Booking booking, Room room, Facility facility, EffectiveStatus status, String bookerName) {}
