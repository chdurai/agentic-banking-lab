CREATE TABLE alert_admission (
    tenant_id VARCHAR(64) PRIMARY KEY
);

CREATE TABLE alert_receipt (
    alert_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    signal_json CLOB NOT NULL,
    owner_token VARCHAR(64),
    failure_category VARCHAR(100),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX ix_alert_pending ON alert_receipt (tenant_id, status, created_at);
CREATE INDEX ix_alert_correlation ON alert_receipt (tenant_id, fingerprint, status, updated_at);
