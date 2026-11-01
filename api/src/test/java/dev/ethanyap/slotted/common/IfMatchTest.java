package dev.ethanyap.slotted.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IfMatchTest {

  @Test
  void acceptsPlainQuotedAndWeakVersions() {
    assertThat(IfMatch.parse("3")).isEqualTo(3L);
    assertThat(IfMatch.parse("\"3\"")).isEqualTo(3L);
    assertThat(IfMatch.parse("W/\"3\"")).isEqualTo(3L);
  }

  @Test
  void aMissingHeaderMeansNoCheck() {
    assertThat(IfMatch.parse(null)).isNull();
    assertThat(IfMatch.parse(" ")).isNull();
    IfMatch.check(null, 7);
  }

  @Test
  void anythingElseIsInvalid() {
    assertThatThrownBy(() -> IfMatch.parse("abc"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
  }

  @Test
  void aDifferentVersionIsAConflict() {
    IfMatch.check(4L, 4);
    assertThatThrownBy(() -> IfMatch.check(3L, 4))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VERSION_CONFLICT));
  }
}
