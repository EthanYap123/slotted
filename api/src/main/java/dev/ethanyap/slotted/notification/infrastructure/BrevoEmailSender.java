package dev.ethanyap.slotted.notification.infrastructure;

import dev.ethanyap.slotted.notification.application.EmailSender;
import dev.ethanyap.slotted.notification.domain.EmailContent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sends through Brevo's transactional email API ({@code POST /v3/smtp/email}, docs/COSTS.md). Brevo
 * answers 201 when it accepts an email. A 400 means this email can never be sent, for example a
 * malformed address; anything else (429 rate limit, 5xx, network) is worth another try.
 */
public class BrevoEmailSender implements EmailSender {

  static final String ENDPOINT = "https://api.brevo.com/v3/smtp/email";

  private final RestClient http;
  private final EmailProperties properties;

  public BrevoEmailSender(RestClient.Builder builder, EmailProperties properties) {
    this.http = builder.build();
    this.properties = properties;
  }

  @Override
  public Result send(EmailContent email) {
    var recipient = new LinkedHashMap<String, String>();
    recipient.put("email", email.toAddress());
    if (email.toName() != null && !email.toName().isBlank()) {
      recipient.put("name", truncate(email.toName()));
    }
    var body = new LinkedHashMap<String, Object>();
    body.put("sender", Map.of("email", properties.fromAddress(), "name", properties.fromName()));
    body.put("to", new ArrayList<>(List.of(recipient)));
    body.put("subject", email.subject());
    body.put("htmlContent", email.htmlBody());
    body.put("textContent", email.textBody());
    try {
      http.post()
          .uri(ENDPOINT)
          .header("api-key", properties.brevoApiKey())
          .contentType(MediaType.APPLICATION_JSON)
          .accept(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .toBodilessEntity();
      return new Result.Sent();
    } catch (RestClientResponseException e) {
      var reason =
          "Brevo answered " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString();
      return isPermanent(e.getStatusCode())
          ? new Result.Refused(reason)
          : new Result.TryAgain(reason);
    } catch (RestClientException e) {
      return new Result.TryAgain("Could not reach Brevo: " + e.getMessage());
    }
  }

  private static boolean isPermanent(HttpStatusCode status) {
    return status.value() == 400;
  }

  /** Brevo limits names to 70 characters. */
  private static String truncate(String name) {
    return name.length() <= 70 ? name : name.substring(0, 70);
  }
}
