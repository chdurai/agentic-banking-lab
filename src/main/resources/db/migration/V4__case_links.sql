CREATE TABLE case_link (
  id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  from_case VARCHAR(64) NOT NULL REFERENCES cases(case_id),
  to_case VARCHAR(64) NOT NULL REFERENCES cases(case_id),
  type VARCHAR(40) NOT NULL,
  created_by VARCHAR(128) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT uq_case_link UNIQUE (tenant_id, from_case, to_case, type),
  CONSTRAINT ck_case_link_type CHECK (type IN ('LOAN_GRIEVANCE', 'DEPENDENCY_DEGRADED', 'DOCUMENT_EVIDENCE')),
  CONSTRAINT ck_case_link_distinct CHECK (from_case <> to_case)
);
CREATE INDEX ix_case_link_source ON case_link (tenant_id, from_case, id);
CREATE INDEX ix_case_link_target ON case_link (tenant_id, to_case, id);
