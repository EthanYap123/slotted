package dev.ethanyap.slotted.room.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ethanyap.slotted.common.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** BR-2 and BR-7 as they apply to room settings. */
class RoomTest {

  @Test
  void newRoomsAreActiveWithATwoHourLimitByDefault() {
    var room = room(4, null);

    assertThat(room.isActive()).isTrue();
    assertThat(room.getMaxBookingMinutes()).isEqualTo(120);
  }

  @ParameterizedTest
  @ValueSource(ints = {30, 60, 90, 240})
  void maximumBookingLengthIsThirtyToTwoHundredFortyInStepsOfThirty(int minutes) {
    assertThat(room(4, minutes).getMaxBookingMinutes()).isEqualTo(minutes);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 15, 45, 270})
  void otherMaximumBookingLengthsAreRejected(int minutes) {
    assertThatThrownBy(() -> room(4, minutes)).isInstanceOf(ApiException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 501})
  void capacityIsOneToFiveHundred(int capacity) {
    assertThatThrownBy(() -> room(capacity, null)).isInstanceOf(ApiException.class);
    assertThat(room(1, null).getCapacity()).isEqualTo(1);
    assertThat(room(500, null).getCapacity()).isEqualTo(500);
  }

  @Test
  void aRoomNeedsANameAndAType() {
    assertThatThrownBy(() -> new Room(UUID.randomUUID(), " ", RoomType.STUDY_ROOM, 4, null, "t"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> new Room(UUID.randomUUID(), "Room", null, 4, null, "t"))
        .isInstanceOf(ApiException.class);
  }

  private static Room room(int capacity, Integer maxBookingMinutes) {
    return new Room(
        UUID.randomUUID(), "Room", RoomType.STUDY_ROOM, capacity, maxBookingMinutes, "token");
  }
}
