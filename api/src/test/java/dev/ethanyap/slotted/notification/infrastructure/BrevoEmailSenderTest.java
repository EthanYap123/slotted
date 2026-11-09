package dev.ethanyap.slotted.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import dev.ethanyap.slotted.notification.application.EmailSender.Result;
import dev.ethanyap.slotted.notification.domain.EmailContent;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The Brevo request, and how its answers are read, against a fake server. */
class BrevoEmailSenderTest {

  private static final EmailContent EMAIL =
      new EmailContent(
          "jane@school.sg", "Jane Tan", "BOOKING_CONFIRMED", "Booked", "Text", "<p>Html</p>", null);

  private MockRestServiceServer server;
  private BrevoEmailSender sender;

  @BeforeEach
  void setUp() {
    var builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    sender =
        new BrevoEmailSender(
            builder,
            new EmailProperties(
                "bookings@school.sg",
                "Slotted",
                "test-api-key",
                250,
                List.of(),
                "http://localhost:5173",
                Duration.ofDays(30)));
  }

  @Test
  void sendsTheEmailInBrevosFormat() {
    server
        .expect(requestTo(BrevoEmailSender.ENDPOINT))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("api-key", "test-api-key"))
        .andExpect(jsonPath("$.sender.email").value("bookings@school.sg"))
        .andExpect(jsonPath("$.sender.name").value("Slotted"))
        .andExpect(jsonPath("$.to[0].email").value("jane@school.sg"))
        .andExpect(jsonPath("$.to[0].name").value("Jane Tan"))
        .andExpect(jsonPath("$.subject").value("Booked"))
        .andExpect(jsonPath("$.htmlContent").value("<p>Html</p>"))
        .andExpect(jsonPath("$.textContent").value("Text"))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"messageId\":\"<1@relay>\"}"));

    assertThat(sender.send(EMAIL)).isInstanceOf(Result.Sent.class);
    server.verify();
  }

  @Test
  void aBadRequestIsRefusedForGood() {
    server
        .expect(requestTo(BrevoEmailSender.ENDPOINT))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"code\":\"invalid_parameter\"}"));

    assertThat(sender.send(EMAIL))
        .isInstanceOfSatisfying(
            Result.Refused.class, r -> assertThat(r.reason()).contains("400", "invalid_parameter"));
  }

  @Test
  void rateLimitsOutagesAndNetworkErrorsAreTriedAgain() {
    server
        .expect(requestTo(BrevoEmailSender.ENDPOINT))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    assertThat(sender.send(EMAIL)).isInstanceOf(Result.TryAgain.class);

    server.reset();
    server
        .expect(requestTo(BrevoEmailSender.ENDPOINT))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
    assertThat(sender.send(EMAIL)).isInstanceOf(Result.TryAgain.class);

    server.reset();
    server
        .expect(requestTo(BrevoEmailSender.ENDPOINT))
        .andRespond(withException(new IOException("connection reset")));
    assertThat(sender.send(EMAIL)).isInstanceOf(Result.TryAgain.class);
  }

  @Test
  void theLogNeverHoldsAFullAddress() {
    assertThat(LoggingEmailSender.mask("jane.doe@school.sg")).isEqualTo("j***@school.sg");
    assertThat(LoggingEmailSender.mask("@nobody")).isEqualTo("***");
  }
}
