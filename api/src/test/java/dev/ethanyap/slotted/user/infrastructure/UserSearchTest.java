package dev.ethanyap.slotted.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserSearchTest {

  @Test
  void likeWildcardsAndTheEscapeCharacterAreEscaped() {
    assertThat(UserSearch.escapeLike("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
  }

  @Test
  void blankTextAndMissingRoleAddNoFilter() {
    assertThat(UserSearch.textContains("   ")).isNull();
    assertThat(UserSearch.textContains(null)).isNull();
    assertThat(UserSearch.hasRole(null)).isNull();
  }
}
