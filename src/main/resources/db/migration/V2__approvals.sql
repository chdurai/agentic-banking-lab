CREATE TABLE approvals (
  id VARCHAR(64) PRIMARY KEY,
  flow VARCHAR(32) NOT NULL,
  subject_id VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  body CLOB NOT NULL
);
CREATE INDEX ix_approvals_status ON approvals (status, created_at);
CREATE INDEX ix_approvals_subject ON approvals (flow, subject_id);
