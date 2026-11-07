package dev.ratnesh.payflow;

import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class Api {
  final Payments p;
  final RateLimit limiter;
  final ProviderSimulator provider;

  public Api(Payments p, RateLimit limiter, ProviderSimulator provider) {
    this.p = p;
    this.limiter = limiter;
    this.provider = provider;
  }

  public record Create(long amount, String currency) {}

  public record Refund(long amount) {}

  @PostMapping(value = "/v1/payments", produces = "application/json")
  ResponseEntity<String> create(
      Principal user, @RequestHeader("Idempotency-Key") String key, @RequestBody Create body) {
    limiter.check(user.getName());
    return ResponseEntity.accepted()
        .body(p.create(user.getName(), key, body.amount(), body.currency()));
  }

  @GetMapping("/v1/payments/{id}")
  Map<String, Object> get(Principal user, @PathVariable UUID id) {
    return p.get(user.getName(), id);
  }

  @PostMapping(value = "/v1/payments/{id}/refunds", produces = "application/json")
  ResponseEntity<String> refund(
      Principal user,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Refund body) {
    limiter.check(user.getName());
    return ResponseEntity.accepted().body(p.refund(user.getName(), id, key, body.amount()));
  }

  @GetMapping("/v1/refunds/{id}")
  Map<String, Object> refund(Principal user, @PathVariable UUID id) {
    return p
        .db
        .queryForList(
            "SELECT r.* FROM refund r JOIN payment p ON p.id=r.payment_id WHERE r.id=? AND"
                + " p.merchant=?",
            id,
            user.getName())
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  @GetMapping("/v1/ledger")
  List<Map<String, Object>> ledger(Principal user) {
    return p.db.queryForList(
        "SELECT j.*,e.account,e.side,e.amount FROM journal j JOIN ledger_entry e ON"
            + " e.journal_id=j.id JOIN operation o ON o.id=j.business_id JOIN payment p ON"
            + " p.id=o.payment_id WHERE p.merchant=? ORDER BY j.created_at DESC LIMIT 200",
        user.getName());
  }

  @GetMapping("/ops/status")
  Map<String, Object> status() {
    return Map.of(
        "pendingOperations",
        p.db.queryForObject("SELECT count(*) FROM operation WHERE state<>'DONE'", Long.class),
        "unpublishedEvents",
        p.db.queryForObject("SELECT count(*) FROM outbox WHERE NOT published", Long.class),
        "pendingWebhooks",
        p.db.queryForObject(
            "SELECT count(*) FROM webhook_delivery WHERE NOT delivered", Long.class),
        "unbalancedJournals",
        p.db.queryForObject(
            "SELECT count(*) FROM (SELECT journal_id FROM ledger_entry GROUP BY journal_id HAVING"
                + " sum(CASE WHEN side='DEBIT' THEN amount ELSE -amount END)<>0) q",
            Long.class));
  }

  @PostMapping("/ops/simulator/lose-next-response")
  void lose() {
    provider.loseNextResponse.set(true);
  }

  @RestControllerAdvice
  static class Errors {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", e.getMessage() == null ? "invalid request" : e.getMessage()));
    }

    @ExceptionHandler(org.springframework.dao.TransientDataAccessException.class)
    ResponseEntity<Map<String, String>> retry() {
      return ResponseEntity.status(503)
          .body(Map.of("error", "database busy; retry with the same idempotency key"));
    }
  }
}
