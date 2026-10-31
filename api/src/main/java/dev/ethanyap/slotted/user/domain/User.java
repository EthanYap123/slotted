package dev.ethanyap.slotted.user.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** An account. New accounts are always students; only an admin changes a role (PRD section 3). */
@Entity
@Table(name = "app_user")
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private String email;
  private String passwordHash;
  private String fullName;

  @Enumerated(EnumType.STRING)
  private Role role;

  @Enumerated(EnumType.STRING)
  private UserStatus status;

  private Instant suspensionClearedAt;

  @CreationTimestamp private Instant createdAt;
  @UpdateTimestamp private Instant updatedAt;
  @Version private long version;

  protected User() {}

  private User(String email, String passwordHash, String fullName, Role role) {
    this.email = normalizeEmail(email);
    this.passwordHash = passwordHash;
    this.fullName = fullName.strip();
    this.role = role;
    this.status = UserStatus.ACTIVE;
  }

  public static User student(String email, String passwordHash, String fullName) {
    return new User(email, passwordHash, fullName, Role.STUDENT);
  }

  /** Emails are compared case-insensitively by storing them trimmed and lower-cased. */
  public static String normalizeEmail(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }

  public boolean isActive() {
    return status == UserStatus.ACTIVE;
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getFullName() {
    return fullName;
  }

  public Role getRole() {
    return role;
  }

  public UserStatus getStatus() {
    return status;
  }

  public Instant getSuspensionClearedAt() {
    return suspensionClearedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
