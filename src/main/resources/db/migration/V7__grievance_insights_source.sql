CREATE TABLE grievance_insights_source (
    observation_id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(200) NOT NULL,
    grievance_id VARCHAR(200) NOT NULL,
    project_id VARCHAR(200) NOT NULL,
    handled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    theme VARCHAR(100) NOT NULL,
    eligible BOOLEAN NOT NULL
);
CREATE INDEX grievance_insights_source_case ON grievance_insights_source (tenant_id, grievance_id, handled_at);
