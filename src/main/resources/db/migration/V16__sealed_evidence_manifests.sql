ALTER TABLE loan_investigation ADD COLUMN attempt_count INTEGER;
ALTER TABLE loan_investigation ADD COLUMN attempts_digest VARCHAR(64);
ALTER TABLE loan_evidence_context ADD COLUMN source_count INTEGER;
ALTER TABLE loan_evidence_context ADD COLUMN sources_digest VARCHAR(64);
