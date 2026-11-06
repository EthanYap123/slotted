package dev.ethanyap.slotted.booking.infrastructure;

import dev.ethanyap.slotted.booking.domain.BookingSeries;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingSeriesRepository extends JpaRepository<BookingSeries, UUID> {}
