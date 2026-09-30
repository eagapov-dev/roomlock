package dev.roomlock.booking.api;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The guest a request acts for: the subject of its access token, never a field of its body.
 *
 * <p>This is the whole point of the change that introduced it. While {@code guestId} arrived in the
 * request body, every idempotency guarantee of the system was scoped to a value the caller chose
 * for itself, so a caller could hold a room in any guest's name.
 */
final class Guests {

  private Guests() {}

  /**
   * The guest id of the token subject.
   *
   * <p>{@code booking.guest_id} is a {@code uuid} column, and both {@code
   * booking_guest_id_idempotency_key_key} and {@code booking_no_overlap} are built on it, so a
   * guest id has to be a UUID. A subject that already is one - what Keycloak and most providers
   * issue - is used as it stands, which keeps the id in a booking the same value the identity
   * provider knows the user by. Any other subject is mapped to a UUID instead of refusing a
   * perfectly valid token or migrating the column: the name being hashed is {@code issuer +
   * subject}, so the map is stable across restarts and two providers that happen to use the same
   * subject stay two guests. The digest only has to name, not to protect - nothing is authenticated
   * by this value, the signature of the token already was.
   */
  static UUID idOf(Jwt token) {
    String subject = token.getSubject();
    try {
      return UUID.fromString(subject);
    } catch (IllegalArgumentException notAUuid) {
      String name = token.getClaimAsString("iss") + " " + subject;
      return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
  }
}
