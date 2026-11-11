-- M6: several outbox relays may run at once (each locks the rows it sends with FOR UPDATE SKIP LOCKED).
-- Which relay sent a row, for tracing and for the test that proves no row is sent twice.
ALTER TABLE outbox ADD COLUMN sent_by TEXT;
