package dev.ethanyap.slotted.booking.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes the text of an email once, as plain text and as simple HTML, so both always say the same
 * thing. Everything inserted is escaped in the HTML.
 */
final class BookingEmails {

  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK);
  private static final DateTimeFormatter SHORT_DAY =
      DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.UK);

  private final List<String> text = new ArrayList<>();
  private final List<String> html = new ArrayList<>();

  /** A paragraph. */
  BookingEmails paragraph(String line) {
    text.add(line);
    html.add("<p>" + escape(line) + "</p>");
    return this;
  }

  /** A list, one line per item. */
  BookingEmails list(List<String> items) {
    text.add(String.join("\n", items.stream().map(i -> "- " + i).toList()));
    html.add(
        "<ul>"
            + String.join("", items.stream().map(i -> "<li>" + escape(i) + "</li>").toList())
            + "</ul>");
    return this;
  }

  /** A sentence ending in a link. */
  BookingEmails link(String label, String url) {
    text.add(label + ": " + url);
    html.add(
        "<p>" + escape(label) + ": <a href=\"" + escape(url) + "\">" + escape(url) + "</a></p>");
    return this;
  }

  String text() {
    return String.join("\n\n", text) + "\n";
  }

  String html() {
    return "<!doctype html><html><body style=\"font-family:Arial,sans-serif;font-size:15px;"
        + "line-height:1.5;color:#1c1b18\">"
        + String.join("", html)
        + "</body></html>";
  }

  /** "Wed 11 Nov 2026, 14:00 to 15:00". */
  static String when(Instant start, Instant end, ZoneId zone) {
    return DAY.format(start.atZone(zone))
        + ", "
        + TIME.format(start.atZone(zone))
        + " to "
        + TIME.format(end.atZone(zone));
  }

  /** "Wed 11 Nov, 14:00", for subjects. */
  static String shortWhen(Instant start, ZoneId zone) {
    return SHORT_DAY.format(start.atZone(zone)) + ", " + TIME.format(start.atZone(zone));
  }

  /** "Wed 18 Nov 2026, 10:15". */
  static String moment(Instant at, ZoneId zone) {
    return DAY.format(at.atZone(zone)) + ", " + TIME.format(at.atZone(zone));
  }

  static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
