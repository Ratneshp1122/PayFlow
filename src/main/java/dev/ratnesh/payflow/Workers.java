package dev.ratnesh.payflow;

import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payflow.workers", havingValue = "true", matchIfMissing = true)
public class Workers {
  final Payments p;
  final ProviderSimulator provider;
  final KafkaTemplate<String, String> kafka;

  public Workers(Payments p, ProviderSimulator provider, KafkaTemplate<String, String> kafka) {
    this.p = p;
    this.provider = provider;
    this.kafka = kafka;
  }

  @Scheduled(fixedDelay = 100)
  public void operations() {
    var claims =
        p.tx.execute(
            s ->
                p.db.queryForList(
                    "UPDATE operation SET lease_until=now()+interval '2"
                        + " seconds',token=token+1,attempts=attempts+1,state='PROCESSING' WHERE id"
                        + " IN (SELECT id FROM operation WHERE state<>'DONE' AND lease_until<=now()"
                        + " ORDER BY lease_until LIMIT 32 FOR UPDATE SKIP LOCKED) RETURNING *"));
    for (var op : claims) {
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
  }

  @Scheduled(fixedDelay = 100)
  public void outbox() {
    var claims =
        p.tx.execute(
            s ->
                p.db.queryForList(
                    "UPDATE outbox SET lease_until=now()+interval '15 seconds',token=token+1 WHERE"
                        + " id IN (SELECT id FROM outbox WHERE NOT published AND lease_until<=now()"
                        + " LIMIT 64 FOR UPDATE SKIP LOCKED) RETURNING *"));
    for (var row : claims)
      try {
        kafka
            .send(
                "payflow.lifecycle.v1",
                row.get("payment_id").toString(),
                row.get("payload").toString())
            .get(11, TimeUnit.SECONDS);
        p.db.update(
            "UPDATE outbox SET published=true WHERE id=? AND token=?",
            row.get("id"),
            row.get("token"));
      } catch (Exception e) {
        LoggerFactory.getLogger(getClass()).warn("Outbox retry {}", row.get("id"));
      }
  }
}
