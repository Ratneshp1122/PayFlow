package dev.ratnesh.payflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class BoundedTasksTest {
  @Test
  void reservesCapacityBeforeClaimAndReturnsItAfterCompletion() throws Exception {
    var started = new CountDownLatch(4);
    var release = new CountDownLatch(1);
    var claimed = new AtomicInteger();
    try (var tasks = new BoundedTasks("test-worker", 4)) {
      for (int i = 0; i < 20; i++)
        tasks.dispatch(
            capacity -> {
              claimed.addAndGet(capacity);
              return java.util.stream.IntStream.range(0, capacity).boxed().toList();
            },
            n -> {
              started.countDown();
              try {
                release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertEquals(4, claimed.get());
      assertEquals(4, tasks.inFlight());
      release.countDown();
      org.awaitility.Awaitility.await().until(() -> tasks.inFlight() == 0);
      assertThrows(
          IllegalStateException.class,
          () ->
              tasks.dispatch(
                  n -> {
                    throw new IllegalStateException("claim failed");
                  },
                  n -> {}));
      assertEquals(0, tasks.inFlight());
    } finally {
      release.countDown();
    }
  }
}
