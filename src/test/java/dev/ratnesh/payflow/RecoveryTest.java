package dev.ratnesh.payflow;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {"payflow.workers=false", "spring.kafka.listener.auto-startup=false", "payflow.profiling=true"})
class RecoveryTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired Payments p;
  @Autowired ProviderSimulator provider;
  @Autowired Webhooks hooks;

  UUID id(String body) throws Exception {
    return UUID.fromString(p.json.readTree(body).path("id").asText());
  }

  UUID captured() throws Exception {
    UUID id = id(p.create("merchant-demo", UUID.randomUUID().toString(), 25000, "INR"));
    p.finalizeOperation(id, 0, provider.execute(id));
    return id;
  }

  @Test
  void concurrentRetriesCreateExactlyOneResource() throws Exception {
    String key = UUID.randomUUID().toString();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<UUID>>();
      for (int i = 0; i < 32; i++) tasks.add(() -> id(p.create("merchant-demo", key, 100, "INR")));
      var ids = new HashSet<UUID>();
      for (var f : pool.invokeAll(tasks)) ids.add(f.get());
      assertEquals(1, ids.size());
    }
    assertThrows(Exception.class, () -> p.create("merchant-demo", key, 101, "INR"));
  }

  @Test
  void responseLossAndWorkerRestartDoNotDoubleCapture() throws Exception {
    UUID id = id(p.create("merchant-demo", UUID.randomUUID().toString(), 100, "INR"));
    provider.loseNextResponse.set(true);
    assertThrows(Exception.class, () -> provider.execute(id));
    p.db.update("UPDATE operation SET token=1 WHERE id=?", id);
    p.finalizeOperation(id, 0, "SUCCEEDED");
    assertEquals("CREATED", p.get("merchant-demo", id).get("state"));
    p.finalizeOperation(id, 1, provider.execute(id));
    p.finalizeOperation(id, 1, provider.execute(id));
    assertEquals(
        1,
        p.db.queryForObject("SELECT count(*) FROM journal WHERE business_id=?", Integer.class, id));
    assertEquals(
        1,
        p.db.queryForObject(
            "SELECT count(*) FROM provider_result WHERE operation_id=?", Integer.class, id));
  }

  @Test
  void concurrentRefundsCannotOverdraw() throws Exception {
    UUID payment = captured();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var results =
          pool.invokeAll(
              List.<Callable<Boolean>>of(
                  () -> {
                    try {
                      p.refund("merchant-demo", payment, "a", 20000);
                      return true;
                    } catch (Exception e) {
                      return false;
                    }
                  },
                  () -> {
                    try {
                      p.refund("merchant-demo", payment, "b", 20000);
                      return true;
                    } catch (Exception e) {
                      return false;
                    }
                  }));
      assertEquals(
          1,
          results.stream()
              .filter(
                  f -> {
                    try {
                      return f.get();
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .count());
    }
  }

  @Test
  void unbalancedAndMutableLedgerAreRejected() throws Exception {
    UUID payment = captured();
    assertThrows(
        Exception.class,
        () ->
            p.tx.executeWithoutResult(
                s ->
                    p.db.update(
                        "INSERT INTO journal(id,business_id,currency) VALUES (?,?,'INR')",
                        UUID.randomUUID(),
                        UUID.randomUUID())));
    assertThrows(
        Exception.class,
        () ->
            p.db.update(
                "UPDATE ledger_entry SET amount=1 WHERE journal_id IN (SELECT id FROM journal WHERE"
                    + " business_id=?)",
                payment));
  }

  @Test
  void duplicateEventsCreateOneWebhookAndFailedDeliveryDoesNotAffectPayment() throws Exception {
    UUID payment = captured();
    String body =
        p.db.queryForObject("SELECT payload FROM outbox WHERE payment_id=?", String.class, payment);
    hooks.consume(body);
    hooks.consume(body);
    UUID event = UUID.fromString(p.json.readTree(body).path("eventId").asText());
    assertEquals(
        1,
        p.db.queryForObject(
            "SELECT count(*) FROM webhook_delivery WHERE event_id=?", Integer.class, event));
    assertEquals("SUCCEEDED", p.get("merchant-demo", payment).get("state"));
  }

  @Test
  void reorderedSignedCallbacksCannotDowngradeSuccess() throws Exception {
    UUID payment = captured();
    String body =
        p.encode(Map.of("eventId", UUID.randomUUID(), "operationId", payment, "status", "FAILED"));
    String ts = Long.toString(java.time.Instant.now().getEpochSecond());
    hooks.incoming(ts, hooks.sign(ts, body), body);
    hooks.incoming(ts, hooks.sign(ts, body), body);
    assertEquals("SUCCEEDED", p.get("merchant-demo", payment).get("state"));
    assertThrows(Exception.class, () -> hooks.incoming(ts, "invalid", body));
  }

  @Test
  void merchantIsolation() throws Exception {
    UUID payment = captured();
    assertThrows(Exception.class, () -> p.get("other-merchant", payment));
    assertThrows(Exception.class, () -> p.refund("other-merchant", payment, "k", 10));
  }

  @Test
  void failedWebhookRetriesWithoutRepostingLedger() throws Exception {
    UUID payment = captured();
    String body =
        p.db.queryForObject("SELECT payload FROM outbox WHERE payment_id=?", String.class, payment);
    hooks.consume(body);
    UUID event = UUID.fromString(p.json.readTree(body).path("eventId").asText());
    var code = new java.util.concurrent.atomic.AtomicInteger(503);
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(code.get(), -1);
          exchange.close();
        });
    server.start();
    try {
      var delivery =
          new Webhooks(
              p,
              "test-secret",
              "http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
              true);
      delivery.deliver();
      assertFalse(
          p.db.queryForObject(
              "SELECT delivered FROM webhook_delivery WHERE event_id=?", Boolean.class, event));
      p.db.update("UPDATE webhook_delivery SET next_attempt=now() WHERE event_id=?", event);
      code.set(200);
      delivery.deliver();
      assertTrue(
          p.db.queryForObject(
              "SELECT delivered FROM webhook_delivery WHERE event_id=?", Boolean.class, event));
      assertEquals(
          1,
          p.db.queryForObject(
              "SELECT count(*) FROM journal WHERE business_id=?", Integer.class, payment));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void entryCannotBeAppendedToAnAlreadyCommittedJournal() throws Exception {
    UUID payment = captured();
    UUID journal =
        p.db.queryForObject("SELECT id FROM journal WHERE business_id=?", UUID.class, payment);
    assertThrows(
        Exception.class,
        () ->
            p.tx.executeWithoutResult(
                s ->
                    p.db.update(
                        "INSERT INTO ledger_entry VALUES"
                            + " (?,'extra-debit','DEBIT',10),(?,'extra-credit','CREDIT',10)",
                        journal,
                        journal)));
  }
}
