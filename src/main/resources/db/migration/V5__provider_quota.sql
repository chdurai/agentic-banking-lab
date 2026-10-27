CREATE TABLE provider_quota (
  provider VARCHAR(128) NOT NULL,
  window_start BIGINT NOT NULL,
  reserved_tokens BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (provider, window_start),
  CONSTRAINT ck_provider_quota_tokens CHECK (reserved_tokens >= 0)
);
