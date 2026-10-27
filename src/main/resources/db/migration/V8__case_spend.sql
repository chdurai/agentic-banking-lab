CREATE TABLE case_spend_account (
    tenant_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(64) NOT NULL,
    token_limit BIGINT NOT NULL,
    cost_limit_micros BIGINT NOT NULL,
    tokens_debited BIGINT NOT NULL,
    cost_debited_micros BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, case_id),
    CHECK (token_limit >= 0),
    CHECK (cost_limit_micros >= -1),
    CHECK (tokens_debited >= 0),
    CHECK (cost_debited_micros >= 0)
);
CREATE TABLE case_spend_attempt (
    attempt_id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(64) NOT NULL,
    tokens_debited BIGINT NOT NULL,
    cost_debited_micros BIGINT NOT NULL,
    settled BOOLEAN NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    FOREIGN KEY (tenant_id, case_id) REFERENCES case_spend_account (tenant_id, case_id),
    CHECK (tokens_debited >= 0),
    CHECK (cost_debited_micros >= 0)
);
CREATE INDEX case_spend_attempt_case ON case_spend_attempt (tenant_id, case_id);
