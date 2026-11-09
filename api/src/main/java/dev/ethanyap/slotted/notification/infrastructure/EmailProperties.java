package dev.ethanyap.slotted.notification.infrastructure;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code slotted.email.*}.
 *
 * @param fromAddress the sender; with Brevo it must be a sender verified in the Brevo account
 * @param brevoApiKey empty means emails are only written to the log
 * @param dailyCap most emails sent per UTC day, kept under Brevo's 300 (docs/COSTS.md)
 * @param skipDomains reserved domains that are never sent to, such as the demo accounts'
 * @param webUrl the web app, for links in emails
 * @param retention how long finished emails are kept before the sweep deletes them
 */
@Validated
@ConfigurationProperties("slotted.email")
public record EmailProperties(
    @NotBlank String fromAddress,
    @NotBlank String fromName,
    String brevoApiKey,
    @Min(0) int dailyCap,
    @NotNull List<String> skipDomains,
    @NotBlank String webUrl,
    @NotNull Duration retention) {

  public EmailProperties {
    brevoApiKey = brevoApiKey == null ? "" : brevoApiKey.strip();
  }
}
