ALTER TABLE cases ADD COLUMN tenant_id VARCHAR(64) DEFAULT 'dev' NOT NULL;
ALTER TABLE cases ADD COLUMN request_json TEXT;
ALTER TABLE cases DROP CONSTRAINT uq_cases_idem;
ALTER TABLE cases ADD CONSTRAINT uq_cases_tenant_idem UNIQUE (tenant_id, flow, idempotency_key);
CREATE INDEX ix_cases_tenant_status ON cases (tenant_id, status);
ALTER TABLE approvals ADD COLUMN tenant_id VARCHAR(64) DEFAULT 'dev' NOT NULL;
ALTER TABLE approvals ADD COLUMN case_id VARCHAR(64);
CREATE INDEX ix_approvals_tenant ON approvals (tenant_id, status);
