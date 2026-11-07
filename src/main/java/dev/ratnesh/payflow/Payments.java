package dev.ratnesh.payflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class Payments {
  final JdbcTemplate db;
  final TransactionTemplate tx;
  final ObjectMapper json;

  public Payments(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json) {
    this.db = db;
    this.tx = tx;
    this.json = json;
  }

  public String encode(Object v) {
    try {
      return json.writeValueAsString(v);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Object> get(String merchant, UUID id) {
    return db.queryForList("SELECT * FROM payment WHERE id=? AND merchant=?", id, merchant).stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  public String create(String merchant, String key, long amount, String currency) {
    Rules.money(amount, currency);
    return command(
        merchant,
        "payment",
        key,
        Rules.hash(amount + ":" + currency),
        id -> {
          db.update(
              "INSERT INTO payment(id,merchant,amount,currency,state) VALUES (?,?,?,?,'CREATED')",
              id,
              merchant,
              amount,
              currency);
          db.update("INSERT INTO operation(id,payment_id,kind) VALUES (?,?,'CAPTURE')", id, id);
        });
  }

  public String refund(String merchant, UUID payment, String key, long amount) {
    if (amount < 1 || amount > 1_000_000_000_000L)
      throw new IllegalArgumentException("invalid refund amount");
    return command(
        merchant,
        "refund:" + payment,
        key,
        Rules.hash(Long.toString(amount)),
        id -> {
          var rows =
              db.queryForList(
                  "SELECT * FROM payment WHERE id=? AND merchant=? FOR UPDATE", payment, merchant);
          if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
          var p = rows.getFirst();
          if (!p.get("state").equals("SUCCEEDED")
              || amount
                  > Rules.available(
                      ((Number) p.get("amount")).longValue(),
                      ((Number) p.get("refunded")).longValue(),
                      ((Number) p.get("reserved")).longValue()))
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "insufficient refundable balance");
          db.update("UPDATE payment SET reserved=reserved+? WHERE id=?", amount, payment);
          db.update("INSERT INTO refund VALUES (?,?,?,'PENDING')", id, payment, amount);
          db.update(
              "INSERT INTO operation(id,payment_id,refund_id,kind) VALUES (?,?,?,'REFUND')",
              id,
              payment,
              id);
        });
  }

  private String command(
      String merchant,
      String scope,
      String key,
      String hash,
      java.util.function.Consumer<UUID> action) {
    if (key == null || key.isBlank() || key.length() > 128)
      throw new IllegalArgumentException("Idempotency-Key must contain 1-128 characters");
    return tx.execute(
        status -> {
          db.execute("SET LOCAL lock_timeout='3s'");
          UUID id = UUID.randomUUID();
          String response = encode(Map.of("id", id, "status", "ACCEPTED"));
          int inserted =
              db.update(
                  "INSERT INTO idempotency VALUES (?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
                  merchant,
                  scope,
                  key,
                  hash,
                  id,
                  response);
          if (inserted == 0) {
            var previous =
                db.queryForMap(
                    "SELECT request_hash,response::text FROM idempotency WHERE merchant=? AND"
                        + " scope=? AND key=?",
                    merchant,
                    scope,
                    key);
            if (!hash.equals(previous.get("request_hash")))
              throw new ResponseStatusException(
                  HttpStatus.CONFLICT, "key reused with a different request");
            return previous.get("response").toString();
          }
          action.accept(id);
          return response;
        });
  }

  public void finalizeOperation(UUID id, long token, String result) {
    tx.executeWithoutResult(
        status -> {
          var op = db.queryForMap("SELECT * FROM operation WHERE id=? FOR UPDATE", id);
          if (op.get("state").equals("DONE") || ((Number) op.get("token")).longValue() != token)
            return;
          UUID payment = (UUID) op.get("payment_id");
          var p = db.queryForMap("SELECT * FROM payment WHERE id=? FOR UPDATE", payment);
          boolean refund = op.get("kind").equals("REFUND");
          long amount = ((Number) p.get("amount")).longValue();
          if (refund) {
            var r =
                db.queryForMap("SELECT * FROM refund WHERE id=? FOR UPDATE", op.get("refund_id"));
            amount = ((Number) r.get("amount")).longValue();
            db.update("UPDATE refund SET state=? WHERE id=?", result, op.get("refund_id"));
            db.update(
                "UPDATE payment SET reserved=reserved-?,refunded=refunded+? WHERE id=?",
                amount,
                result.equals("SUCCEEDED") ? amount : 0,
                payment);
          } else db.update("UPDATE payment SET state=? WHERE id=?", result, payment);
          if (result.equals("SUCCEEDED")) {
            UUID journal = UUID.randomUUID();
            db.update(
                "INSERT INTO journal(id,business_id,currency) VALUES (?,?,?)",
                journal,
                id,
                p.get("currency"));
            String prefix = p.get("merchant") + ":" + p.get("currency") + ":";
            db.update(
                "INSERT INTO ledger_entry VALUES (?,?,?,?),(?,?,?,?)",
                journal,
                prefix + "clearing",
                refund ? "CREDIT" : "DEBIT",
                amount,
                journal,
                prefix + "merchant-payable",
                refund ? "DEBIT" : "CREDIT",
                amount);
          }
          UUID event = UUID.randomUUID();
          String body =
              encode(
                  Map.of(
                      "eventId",
                      event,
                      "paymentId",
                      payment,
                      "operationId",
                      id,
                      "type",
                      op.get("kind") + "_" + result));
          db.update(
              "INSERT INTO outbox(id,payment_id,payload) VALUES (?,?,?)", event, payment, body);
          db.update("UPDATE operation SET state='DONE' WHERE id=?", id);
        });
  }
}
