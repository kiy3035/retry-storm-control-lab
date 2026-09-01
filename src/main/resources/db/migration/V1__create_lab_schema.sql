CREATE SCHEMA retry_lab;

CREATE TABLE retry_lab.lab_metadata (
    metadata_key VARCHAR(100) PRIMARY KEY,
    metadata_value VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO retry_lab.lab_metadata (metadata_key, metadata_value)
VALUES ('schema-purpose', 'Synthetic local retry-storm experiment data only');
