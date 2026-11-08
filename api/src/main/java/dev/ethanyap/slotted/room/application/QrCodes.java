package dev.ethanyap.slotted.room.application;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;

/** Draws QR codes as SVG, so they print sharply at any size (PRD MG-5). */
final class QrCodes {

  /** Blank modules around the code, as the QR standard asks for. */
  private static final int QUIET_ZONE = 4;

  /** Height of the caption under the code, in modules. */
  private static final int CAPTION = 6;

  private QrCodes() {}

  /** A QR code for {@code content}, with {@code caption} printed under it. */
  static String svg(String content, String caption) {
    try {
      var matrix =
          new QRCodeWriter()
              .encode(
                  content,
                  BarcodeFormat.QR_CODE,
                  0,
                  0,
                  Map.of(
                      EncodeHintType.MARGIN,
                      QUIET_ZONE,
                      EncodeHintType.ERROR_CORRECTION,
                      ErrorCorrectionLevel.M,
                      EncodeHintType.CHARACTER_SET,
                      "UTF-8"));
      var size = matrix.getWidth();
      var path = new StringBuilder();
      for (var y = 0; y < size; y++) {
        for (var x = 0; x < size; x++) {
          if (matrix.get(x, y)) {
            path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
          }
        }
      }
      var height = size + CAPTION;
      var text = escape(caption);
      return """
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges" \
          role="img" aria-label="Check-in QR code for %s">\
          <rect width="100%%" height="100%%" fill="#ffffff"/>\
          <path d="%s" fill="#000000"/>\
          <text x="%d" y="%d" font-family="sans-serif" font-size="3" text-anchor="middle" \
          fill="#000000">%s</text></svg>"""
          .formatted(size, height, text, path, size / 2, size + CAPTION / 2, text);
    } catch (WriterException e) {
      throw new IllegalStateException("Could not draw a QR code", e);
    }
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
