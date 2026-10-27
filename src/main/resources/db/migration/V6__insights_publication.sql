CREATE TABLE IF NOT EXISTS grievance_insights_publication (
    tenant_id VARCHAR(64) NOT NULL,
    week_start DATE NOT NULL,
    payload_json TEXT NOT NULL,
    published_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, week_start)
);
