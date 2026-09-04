CREATE TABLE retry_lab.dead_letters (
    message_id UUID PRIMARY KEY,
    payload TEXT NOT NULL,
    failures_before_success INTEGER NOT NULL CHECK (failures_before_success >= 0),
    published_at TIMESTAMPTZ NOT NULL,
    failed_at TIMESTAMPTZ NOT NULL,
    failure_code VARCHAR(64) NOT NULL,
    original_attempts INTEGER NOT NULL CHECK (original_attempts >= 1),
    state VARCHAR(20) NOT NULL CHECK (state IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    replay_attempts INTEGER NOT NULL DEFAULT 0,
    reprocess_count INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX ix_dead_letters_failed_at ON retry_lab.dead_letters(failed_at, message_id);
