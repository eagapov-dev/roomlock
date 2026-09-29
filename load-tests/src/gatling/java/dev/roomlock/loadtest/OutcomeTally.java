package dev.roomlock.loadtest;

import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * The counting behind {@link HoldOutcomes} and {@link PayOutcomes}: one counter per outcome,
 * printed when the simulation is over. Gatling itself only reports OK/KO, while the expected 409s -
 * a lost race, a declined charge - are exactly the numbers a run is compared on.
 */
final class OutcomeTally {

  private static final String PROBLEM_PREFIX = "urn:roomlock:problem:";

  private final Map<String, LongAdder> counts = new ConcurrentSkipListMap<>();

  void count(String outcome) {
    counts.computeIfAbsent(outcome, key -> new LongAdder()).increment();
  }

  long total() {
    return counts.values().stream().mapToLong(LongAdder::sum).sum();
  }

  /** The block printed after the run, next to the path of the Gatling report. */
  String report(String heading) {
    StringBuilder text = new StringBuilder(heading).append(':');
    counts.forEach(
        (outcome, count) ->
            text.append(System.lineSeparator())
                .append("  ")
                .append(outcome)
                .append(" = ")
                .append(count.sum()));
    return text.append(System.lineSeparator()).append("  total = ").append(total()).toString();
  }

  /** The tail of a {@code urn:roomlock:problem:} type, which is what tells two 409s apart. */
  static String problemName(String problemType) {
    if (problemType == null) {
      return "without a problem type";
    }
    return problemType.startsWith(PROBLEM_PREFIX)
        ? problemType.substring(PROBLEM_PREFIX.length())
        : problemType;
  }
}
