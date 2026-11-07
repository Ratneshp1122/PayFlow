package dev.ratnesh.payflow;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable simulator: provider acceptance is a separate committed transaction. */
@Component
public class ProviderSimulator {
  final JdbcTemplate db;
  final TransactionTemplate tx;
  public final AtomicBoolean loseNextResponse = new AtomicBoolean();

  public ProviderSimulator(JdbcTemplate db, TransactionTemplate tx) {
    this.db = db;
    this.tx = tx;
  }

  public String execute(UUID operation) {
    String result =
        tx.execute(
            s -> {
              db.update(
                  "INSERT INTO provider_result(operation_id,result) VALUES (?,'SUCCEEDED') ON"
                      + " CONFLICT DO NOTHING",
                  operation);
              return db.queryForObject(
                  "SELECT result FROM provider_result WHERE operation_id=?",
                  String.class,
                  operation);
            });
    if (loseNextResponse.compareAndSet(true, false))
      throw new IllegalStateException("simulated response loss after durable provider success");
    return result;
  }
}
