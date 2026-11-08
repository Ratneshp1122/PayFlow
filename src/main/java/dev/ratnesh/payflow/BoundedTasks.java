package dev.ratnesh.payflow;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import org.slf4j.LoggerFactory;

/** Reserve capacity BEFORE leasing database work. No unbounded queue of expiring leases. */
public final class BoundedTasks implements AutoCloseable {
  private final int limit;
  private final Semaphore permits;
  private final ExecutorService executor;

  public BoundedTasks(String name, int limit) {
    if (limit < 1 || limit > 16) throw new IllegalArgumentException("concurrency must be 1..16");
    this.limit = limit;
    permits = new Semaphore(limit);
    var sequence = new AtomicInteger();
    executor =
        new ThreadPoolExecutor(
            limit,
            limit,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(limit),
            r -> new Thread(r, name + "-" + sequence.incrementAndGet()));
  }

  public int inFlight() {
    return limit - permits.availablePermits();
  }

  public <T> void dispatch(IntFunction<List<T>> claim, Consumer<T> work) {
    int capacity = permits.drainPermits();
    if (capacity == 0) return;
    List<T> rows;
    try {
      rows = claim.apply(capacity);
      if (rows.size() > capacity)
        throw new IllegalStateException("claim exceeded reserved capacity");
    } catch (RuntimeException e) {
      permits.release(capacity);
      throw e;
    }
    permits.release(capacity - rows.size());
    for (T row : rows) {
      try {
        executor.execute(
            () -> {
              try {
                work.accept(row);
              } catch (Exception e) {
                LoggerFactory.getLogger(getClass()).warn("Leased work will retry after failure", e);
              } finally {
                permits.release();
              }
            });
      } catch (RejectedExecutionException shutdown) {
        permits.release();
      }
    }
  }

  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
