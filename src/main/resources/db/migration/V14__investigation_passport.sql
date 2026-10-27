CREATE TABLE IF NOT EXISTS loan_evidence_passport (
    tenant_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(200) NOT NULL,
    snapshot CLOB NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, case_id)
);
