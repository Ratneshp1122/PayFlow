package dev.ratnesh.payflow;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payflow.workers", havingValue = "true", matchIfMissing = true)
public class Workers implements AutoCloseable {
  final Payments p;
  final ProviderSimulator provider;
  final KafkaTemplate<String, String> kafka;
  final BoundedTasks operations;
  final int outboxBatch;

  public Workers(
      Payments p,
      ProviderSimulator provider,
      KafkaTemplate<String, String> kafka,
      @Value("${payflow.worker-concurrency:4}") int concurrency,
      @Value("${payflow.outbox-batch-size:128}") int outboxBatch) {
    this.p = p;
    this.provider = provider;
    this.kafka = kafka;
    operations = new BoundedTasks("payflow-provider", concurrency);
    if (outboxBatch < 1 || outboxBatch > 512)
      throw new IllegalArgumentException("outbox batch must be 1..512");
    this.outboxBatch = outboxBatch;
  }

  @Scheduled(fixedDelayString = "${payflow.worker-poll-ms:10}")
  public void operations() {
    operations.dispatch(
        capacity ->
            p.tx.execute(
                s ->
                    p.db.queryForList(
                        "UPDATE operation SET lease_until=now()+interval '2"
                            + " seconds',token=token+1,attempts=attempts+1,state='PROCESSING' WHERE"
                            + " id IN (SELECT id FROM operation WHERE state<>'DONE' AND"
                            + " lease_until<=now() ORDER BY lease_until LIMIT ? FOR UPDATE SKIP"
                            + " LOCKED) RETURNING *",
                        capacity)),
        this::process);
  }

  private void process(Map<String, Object> op) {
    UUID id = (UUID) op.get("id");
    long token = ((Number) op.get("token")).longValue();
    try {
      p.finalizeOperation(id, token, provider.execute(id));
    } catch (Exception e) {
      p.tx.executeWithoutResult(
          s -> {
            var rows =
                p.db.queryForList(
                    "SELECT * FROM operation WHERE id=? AND token=? AND state<>'DONE' FOR UPDATE",
                    id,
                    token);
            if (rows.isEmpty()) return;
            p.db.update("UPDATE operation SET state='UNKNOWN' WHERE id=?", id);
            if (op.get("kind").equals("CAPTURE"))
              p.db.update(
                  "UPDATE payment SET state='UNKNOWN' WHERE id=? AND state NOT IN"
                      + " ('SUCCEEDED','FAILED')",
                  op.get("payment_id"));
            else
              p.db.update(
                  "UPDATE refund SET state='UNKNOWN' WHERE id=? AND state NOT IN"
                      + " ('SUCCEEDED','FAILED')",
                  op.get("refund_id"));
          });
      LoggerFactory.getLogger(getClass())
          .warn("Operation {} awaits reconciliation: {}", id, e.getClass().getSimpleName());
    }
  }

  @Scheduled(fixedDelay = 100)
  public void outbox() {
    var claims =
        p.tx.execute(
            s ->
                p.db.queryForList(
                    "UPDATE outbox SET lease_until=now()+interval '15 seconds',token=token+1 WHERE"
                        + " id IN (SELECT id FROM outbox WHERE NOT published AND lease_until<=now()"
                        + " LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING *",
                    outboxBatch));
    var futures = new ArrayList<CompletableFuture<?>>();
    for (var row : claims) {
      try {
        futures.add(
            kafka.send(
                "payflow.lifecycle.v1",
                row.get("payment_id").toString(),
                row.get("payload").toString()));
      } catch (Exception e) {
        futures.add(CompletableFuture.failedFuture(e));
      }
    }
    var acknowledged = new ArrayList<Object[]>();
    for (int i = 0; i < claims.size(); i++) {
      var row = claims.get(i);
      try {
        futures.get(i).get(11, TimeUnit.SECONDS);
        acknowledged.add(new Object[] {row.get("id"), row.get("token")});
      } catch (Exception e) {
        LoggerFactory.getLogger(getClass()).warn("Outbox retry {}", row.get("id"));
      }
    }
    if (!acknowledged.isEmpty())
      p.tx.executeWithoutResult(
          s ->
              p.db.batchUpdate(
                  "UPDATE outbox SET published=true WHERE id=? AND token=?", acknowledged));
  }

  @jakarta.annotation.PreDestroy
  @Override
  public void close() {
    operations.close();
  }
}
