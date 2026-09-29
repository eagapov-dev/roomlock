package dev.roomlock.loadtest;

import static io.gatling.javaapi.core.CoreDsl.exec;

import io.gatling.javaapi.core.ChainBuilder;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts how a run's holds ended, one counter per HTTP status and per problem type. The 409s of a
 * lost race are the interesting number of a strategy comparison, and Gatling itself only reports
 * OK/KO, so they are tallied here and printed when the simulation is over.
 */
final class HoldOutcomes {

  private static final String PROBLEM_PREFIX = "urn:roomlock:problem:";

  private final Map<String, LongAdder> counts = new ConcurrentSkipListMap<>();

  /** Reads the status and problem type the hold saved in the session and counts the outcome. */
  ChainBuilder record() {
    return exec(
        session -> {
          count(outcome(session.getInt(Holds.STATUS), session.getString(Holds.PROBLEM_TYPE)));
          return session;
        });
  }

  void count(String outcome) {
    counts.computeIfAbsent(outcome, key -> new LongAdder()).increment();
  }

  long total() {
    return counts.values().stream().mapToLong(LongAdder::sum).sum();
  }

  /** The block printed after the run, next to the path of the Gatling report. */
  String report(String simulation) {
    StringBuilder text =
        new StringBuilder("hold outcomes of ")
            .append(simulation)
            .append(" (strategy=")
            .append(LoadTestSettings.STRATEGY)
            .append("):");
    counts.forEach(
        (outcome, count) ->
            text.append(System.lineSeparator())
                .append("  ")
                .append(outcome)
                .append(" = ")
                .append(count.sum()));
    return text.append(System.lineSeparator()).append("  total = ").append(total()).toString();
  }

  private static String outcome(int status, String problemType) {
    return switch (status) {
      case 201 -> "201 created";
      case 200 -> "200 replayed";
      case 409 -> "409 " + problemName(problemType);
      default -> status + " unexpected";
    };
  }

  private static String problemName(String problemType) {
    if (problemType == null) {
      return "conflict without a problem type";
    }
    return problemType.startsWith(PROBLEM_PREFIX)
        ? problemType.substring(PROBLEM_PREFIX.length())
        : problemType;
  }
}
