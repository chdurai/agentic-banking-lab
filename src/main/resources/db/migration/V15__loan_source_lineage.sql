CREATE TABLE IF NOT EXISTS loan_evidence_context (
    tenant_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(200) NOT NULL,
    snapshot CLOB NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, case_id)
);
CREATE TABLE IF NOT EXISTS loan_source_observation (
    tenant_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(200) NOT NULL,
    observation_id VARCHAR(36) NOT NULL,
    agent_id VARCHAR(80) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    source_name VARCHAR(120) NOT NULL,
    source_version VARCHAR(120) NOT NULL,
    args_hash VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL,
    payload CLOB,
    payload_hash VARCHAR(64),
    recorded_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (tenant_id, observation_id)
);
CREATE INDEX IF NOT EXISTS loan_source_case_idx ON loan_source_observation(tenant_id, case_id);
