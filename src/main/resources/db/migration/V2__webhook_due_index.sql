-- Keep claim scans proportional to pending work rather than delivery history.
-- Predicate matches Webhooks.deliver; expired retries require operator intervention.
CREATE INDEX webhook_delivery_due ON webhook_delivery(next_attempt)
WHERE NOT delivered AND attempts < 20;
