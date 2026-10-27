CREATE TABLE IF NOT EXISTS loan_investigation (
    tenant_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(200) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    state VARCHAR(32) NOT NULL,
    gap VARCHAR(40) NOT NULL,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (tenant_id, case_id),
    UNIQUE (tenant_id, run_id)
);
CREATE TABLE IF NOT EXISTS loan_investigation_attempt (
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    ordinal_no INTEGER NOT NULL,
    kind VARCHAR(16) NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    args_hash VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    payload CLOB,
    payload_hash VARCHAR(64),
    created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (tenant_id, attempt_id),
    UNIQUE (tenant_id, run_id, ordinal_no)
);
