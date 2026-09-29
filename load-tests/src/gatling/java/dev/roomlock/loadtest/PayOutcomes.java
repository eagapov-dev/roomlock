package dev.roomlock.loadtest;

import static io.gatling.javaapi.core.CoreDsl.exec;

import io.gatling.javaapi.core.ChainBuilder;

/**
 * Counts how a run's charges ended, the way {@link HoldOutcomes} counts its holds: an approval, a
 * decline and an unreachable provider are all expected answers of the pay endpoint, and only their
 * tally says what a run actually exercised.
 */
final class PayOutcomes {

  private final OutcomeTally tally = new OutcomeTally();

  /** Reads the status and problem type the pay saved in the session and counts the outcome. */
  ChainBuilder record() {
    return exec(
        session -> {
          count(
              session.contains(Payments.STATUS)
                  ? outcome(
                      session.getInt(Payments.STATUS), session.getString(Payments.PROBLEM_TYPE))
                  : "no answer");
          return session;
        });
  }

  void count(String outcome) {
    tally.count(outcome);
  }

  long total() {
    return tally.total();
  }

  /** The block printed after the run, next to the path of the Gatling report. */
  String report(String simulation) {
    return tally.report(
        "payment outcomes of "
            + simulation
            + " (strategy="
            + LoadTestSettings.STRATEGY
            + ", payment mode="
            + LoadTestSettings.PAYMENT_MODE
            + ")");
  }

  private static String outcome(int status, String problemType) {
    return switch (status) {
      case 200 -> "200 charged";
      case 409 -> "409 " + OutcomeTally.problemName(problemType);
      // Documented: the booking stays PAYING and a reconciliation sweep finishes it.
      case 503 -> "503 " + OutcomeTally.problemName(problemType);
      default -> status + " unexpected";
    };
  }
}
