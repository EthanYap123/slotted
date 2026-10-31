package dev.ethanyap.slotted.user.domain;

/** Disabled users cannot sign in or refresh their session (PRD AD-1). */
public enum UserStatus {
  ACTIVE,
  DISABLED
}
