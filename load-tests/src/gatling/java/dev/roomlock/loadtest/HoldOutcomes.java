package dev.roomlock.loadtest;

import static io.gatling.javaapi.core.CoreDsl.exec;

import io.gatling.javaapi.core.ChainBuilder;

/**
 * Counts how a run's holds ended, one counter per HTTP status and per problem type. The 409s of a
 * lost race are the interesting number of a strategy comparison, and Gatling itself only reports
 * OK/KO, so they are tallied here and printed when the simulation is over.
 */
final class HoldOutcomes {

  private final OutcomeTally tally = new OutcomeTally();

  /** Reads the status and problem type the hold saved in the session and counts the outcome. */
  ChainBuilder record() {
    return exec(
        session -> {
          count(outcome(session.getInt(Holds.STATUS), session.getString(Holds.PROBLEM_TYPE)));
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
        "hold outcomes of " + simulation + " (strategy=" + LoadTestSettings.STRATEGY + ")");
  }

  private static String outcome(int status, String problemType) {
    return switch (status) {
      case 201 -> "201 created";
      case 200 -> "200 replayed";
      case 409 -> "409 " + OutcomeTally.problemName(problemType);
      default -> status + " unexpected";
    };
  }
}
