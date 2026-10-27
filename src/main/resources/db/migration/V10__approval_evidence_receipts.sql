CREATE TABLE approval_evidence_seal (
    tenant_id VARCHAR(64) NOT NULL,
    approval_id VARCHAR(100) NOT NULL REFERENCES approvals(id),
    schema_version INTEGER NOT NULL,
    snapshot_json CLOB NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, approval_id)
);

CREATE TABLE simulated_loan_receipt (
    tenant_id VARCHAR(64) NOT NULL,
    approval_id VARCHAR(100) NOT NULL REFERENCES approvals(id),
    effect_key VARCHAR(100) NOT NULL,
    evidence_sha256 VARCHAR(64) NOT NULL,
    receipt_id VARCHAR(100) NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, approval_id),
    UNIQUE (effect_key),
    UNIQUE (receipt_id)
);
