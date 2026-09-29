package dev.roomlock.booking.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;

/** Runs the same call from several threads that start at the same moment (rule T-8). */
public final class Concurrently {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private Concurrently() {}

  /**
   * Calls {@code call} once per thread, with the index of the call, and returns the results in the
   * order the calls were submitted.
   */
  public static <T> List<T> run(int calls, IntFunction<T> call) {
    try (ExecutorService threads = Executors.newFixedThreadPool(calls)) {
      CyclicBarrier start = new CyclicBarrier(calls);
      List<Future<T>> futures = new ArrayList<>();
      for (int index = 0; index < calls; index++) {
        int current = index;
        futures.add(threads.submit(() -> callAfterBarrier(start, call, current)));
      }
      return results(futures);
    }
  }

  private static <T> T callAfterBarrier(CyclicBarrier start, IntFunction<T> call, int index)
      throws BrokenBarrierException, InterruptedException, TimeoutException {
    start.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    return call.apply(index);
  }

  private static <T> List<T> results(List<Future<T>> futures) {
    List<T> results = new ArrayList<>(futures.size());
    for (Future<T> future : futures) {
      results.add(result(future));
    }
    return results;
  }

  private static <T> T result(Future<T> future) {
    try {
      return future.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for a concurrent call", interrupted);
    } catch (ExecutionException | TimeoutException failed) {
      throw new IllegalStateException("A concurrent call failed", failed);
    }
  }
}
