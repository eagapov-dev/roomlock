package dev.roomlock.booking.payment;

import java.io.IOException;

/** The Google-signed ID token a call to payment-service carries. */
@FunctionalInterface
interface IdTokenSource {

  /**
   * A token for the audience of payment-service that has not expired; the implementation mints a
   * new one when the one it holds has.
   *
   * @throws IOException if no token can be minted, which is never an answer about a charge
   */
  String token() throws IOException;
}
