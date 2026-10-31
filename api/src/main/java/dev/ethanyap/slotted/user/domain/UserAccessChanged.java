package dev.ethanyap.slotted.user.domain;

import java.util.UUID;

/**
 * Published when a user's role or status changes, so existing sessions can be ended. Sessions
 * belong to the auth feature, which listens for this (ADR-0002).
 */
public record UserAccessChanged(UUID userId) {}
