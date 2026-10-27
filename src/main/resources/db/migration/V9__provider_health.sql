CREATE TABLE provider_health (
    provider VARCHAR(128) PRIMARY KEY,
    configuration VARCHAR(200) NOT NULL,
    circuit_state VARCHAR(16) NOT NULL,
    generation BIGINT NOT NULL,
    open_until_ms BIGINT NOT NULL,
    probe_until_ms BIGINT NOT NULL,
    active_probes INTEGER NOT NULL,
    successful_probes INTEGER NOT NULL,
    sample_sequence BIGINT NOT NULL,
    CHECK (circuit_state IN ('CLOSED','OPEN','HALF_OPEN')),
    CHECK (active_probes >= 0),
    CHECK (successful_probes >= 0)
);
CREATE TABLE provider_health_sample (
    provider VARCHAR(128) NOT NULL,
    sample_sequence BIGINT NOT NULL,
    failed BOOLEAN NOT NULL,
    PRIMARY KEY (provider, sample_sequence),
    FOREIGN KEY (provider) REFERENCES provider_health (provider)
);
