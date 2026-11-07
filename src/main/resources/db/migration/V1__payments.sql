CREATE TABLE payment (
 id uuid PRIMARY KEY, merchant text NOT NULL, amount bigint NOT NULL CHECK(amount BETWEEN 1 AND 1000000000000),
 currency text NOT NULL CHECK(currency IN ('INR','USD','EUR')), state text NOT NULL CHECK(state IN ('CREATED','PROCESSING','UNKNOWN','SUCCEEDED','FAILED')),
 refunded bigint NOT NULL DEFAULT 0 CHECK(refunded>=0), reserved bigint NOT NULL DEFAULT 0 CHECK(reserved>=0),
 created_at timestamptz NOT NULL DEFAULT now(), CHECK(refunded+reserved<=amount));
CREATE INDEX payment_merchant ON payment(merchant,created_at,id);
CREATE TABLE refund(id uuid PRIMARY KEY,payment_id uuid NOT NULL REFERENCES payment,amount bigint NOT NULL CHECK(amount>0),state text NOT NULL CHECK(state IN ('PENDING','UNKNOWN','SUCCEEDED','FAILED')));
CREATE TABLE idempotency(merchant text,scope text,key text,request_hash text NOT NULL,resource_id uuid NOT NULL,response jsonb NOT NULL,PRIMARY KEY(merchant,scope,key));
CREATE TABLE operation(id uuid PRIMARY KEY,payment_id uuid NOT NULL REFERENCES payment,refund_id uuid REFERENCES refund,kind text NOT NULL CHECK(kind IN ('CAPTURE','REFUND')),state text NOT NULL DEFAULT 'PENDING',lease_until timestamptz NOT NULL DEFAULT now(),token bigint NOT NULL DEFAULT 0,attempts integer NOT NULL DEFAULT 0);
CREATE INDEX operation_due ON operation(lease_until) WHERE state<>'DONE';
CREATE TABLE provider_result(operation_id uuid PRIMARY KEY REFERENCES operation,result text NOT NULL CHECK(result IN ('SUCCEEDED','FAILED')),created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE journal(id uuid PRIMARY KEY,business_id uuid NOT NULL UNIQUE,currency text NOT NULL,posting_tx bigint NOT NULL DEFAULT txid_current(),created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE ledger_entry(journal_id uuid REFERENCES journal,account text NOT NULL,side text NOT NULL CHECK(side IN ('DEBIT','CREDIT')),amount bigint NOT NULL CHECK(amount>0),PRIMARY KEY(journal_id,account));
CREATE FUNCTION deny_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'ledger is immutable'; END $$;
CREATE TRIGGER immutable_journal BEFORE UPDATE OR DELETE ON journal FOR EACH ROW EXECUTE FUNCTION deny_mutation();
CREATE TRIGGER immutable_entry BEFORE UPDATE OR DELETE ON ledger_entry FOR EACH ROW EXECUTE FUNCTION deny_mutation();
CREATE FUNCTION enforce_posting_transaction() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF (SELECT posting_tx FROM journal WHERE id=NEW.journal_id) IS DISTINCT FROM txid_current() THEN RAISE EXCEPTION 'cannot append to a committed journal'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER entry_posting_transaction BEFORE INSERT ON ledger_entry FOR EACH ROW EXECUTE FUNCTION enforce_posting_transaction();
CREATE FUNCTION check_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE jid uuid; n bigint; balance numeric;
BEGIN
 IF TG_TABLE_NAME='journal' THEN jid:=NEW.id; ELSE jid:=NEW.journal_id; END IF;
 SELECT count(*),sum(CASE WHEN side='DEBIT' THEN amount::numeric ELSE -amount::numeric END) INTO n,balance FROM ledger_entry WHERE journal_id=jid;
 IF n<2 OR balance<>0 THEN RAISE EXCEPTION 'unbalanced or empty journal %',jid; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_balanced AFTER INSERT ON journal DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_journal();
CREATE CONSTRAINT TRIGGER entry_balanced AFTER INSERT ON ledger_entry DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_journal();
CREATE TABLE outbox(id uuid PRIMARY KEY,payment_id uuid NOT NULL REFERENCES payment,payload text NOT NULL,published boolean NOT NULL DEFAULT false,lease_until timestamptz NOT NULL DEFAULT now(),token bigint NOT NULL DEFAULT 0);
CREATE INDEX outbox_due ON outbox(lease_until) WHERE NOT published;
CREATE TABLE consumer_receipt(event_id uuid PRIMARY KEY,received_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE webhook_delivery(event_id uuid PRIMARY KEY REFERENCES consumer_receipt,payload text NOT NULL,attempts int NOT NULL DEFAULT 0,next_attempt timestamptz NOT NULL DEFAULT now(),delivered boolean NOT NULL DEFAULT false,token bigint NOT NULL DEFAULT 0);
CREATE TABLE webhook_inbox(event_id uuid PRIMARY KEY,body_hash text NOT NULL,received_at timestamptz NOT NULL DEFAULT now());
