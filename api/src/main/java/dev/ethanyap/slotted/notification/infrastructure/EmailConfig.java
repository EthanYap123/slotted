package dev.ethanyap.slotted.notification.infrastructure;

import dev.ethanyap.slotted.notification.application.EmailSender;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Picks the email sender: Brevo when a key is set, otherwise the log. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailProperties.class)
class EmailConfig {

  private static final Logger log = LoggerFactory.getLogger(EmailConfig.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @Bean
  EmailSender emailSender(EmailProperties properties, RestClient.Builder builder) {
    if (properties.brevoApiKey().isEmpty()) {
      log.warn("BREVO_API_KEY is not set: emails are written to the log, not sent.");
      return new LoggingEmailSender();
    }
    var client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    var factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(TIMEOUT);
    return new BrevoEmailSender(builder.clone().requestFactory(factory), properties);
  }
}
