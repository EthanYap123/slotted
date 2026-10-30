package dev.ethanyap.slotted;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SlottedApplication {

  public static void main(String[] args) {
    // Instants are stored and handled in UTC; business rules apply the facility's zone (ADR-0003).
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(SlottedApplication.class, args);
  }
}
