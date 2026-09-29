package dev.roomlock.booking.hold;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of the request a booking was created from, as 64 hex characters. A retry with the same
 * idempotency key is a replay only if its hash matches (rule I-2).
 */
public final class RequestHash {

  private static final String ALGORITHM = "SHA-256";
  private static final String SEPARATOR = "|";

  private RequestHash() {}

  public static String of(HoldCommand command) {
    String request =
        String.join(
            SEPARATOR,
            command.roomId().toString(),
            command.guestId().toString(),
            command.stay().checkIn().toString(),
            command.stay().checkOut().toString());
    return HexFormat.of().formatHex(digest().digest(request.getBytes(StandardCharsets.UTF_8)));
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance(ALGORITHM);
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException(ALGORITHM + " is required by the platform", missing);
    }
  }
}
