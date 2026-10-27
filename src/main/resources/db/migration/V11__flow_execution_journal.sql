CREATE TABLE flow_execution_slot (
    tenant_id VARCHAR(64) NOT NULL,
    execution_key VARCHAR(100) NOT NULL,
    flow VARCHAR(32) NOT NULL,
    run_id VARCHAR(64),
    PRIMARY KEY (tenant_id, execution_key, flow)
);

CREATE TABLE flow_execution_run (
    run_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(64),
    execution_key VARCHAR(100) NOT NULL,
    flow VARCHAR(32) NOT NULL,
    owner_token VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    schema_version INTEGER NOT NULL,
    plan_json CLOB NOT NULL,
    plan_sha256 VARCHAR(64) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP
);
CREATE INDEX ix_flow_execution_case ON flow_execution_run (tenant_id, case_id, started_at);

CREATE TABLE flow_node_attempt (
    run_id VARCHAR(64) NOT NULL REFERENCES flow_execution_run(run_id),
    node_id VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    predecessor_json CLOB NOT NULL,
    result_json CLOB,
    result_sha256 VARCHAR(64),
    failure_category VARCHAR(100),
    duration_ms BIGINT,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    PRIMARY KEY (run_id, node_id)
);
