CREATE TABLE IF NOT EXISTS open_metadata.demo_environment_guard (
    environment_uuid CHAR(36) PRIMARY KEY,
    environment_name VARCHAR(64) NOT NULL
);
CREATE TABLE IF NOT EXISTS open_metadata_benchmark_preflight.demo_environment_guard (
    environment_uuid CHAR(36) PRIMARY KEY,
    environment_name VARCHAR(64) NOT NULL
);

INSERT INTO open_metadata.demo_environment_guard (environment_uuid, environment_name)
VALUES ('00000000-0000-0000-0000-00000000d000', 'open-metadata-sync-public-demo')
ON DUPLICATE KEY UPDATE environment_name = VALUES(environment_name);
INSERT INTO open_metadata_benchmark_preflight.demo_environment_guard (environment_uuid, environment_name)
VALUES ('00000000-0000-0000-0000-00000000d000', 'open-metadata-sync-public-demo')
ON DUPLICATE KEY UPDATE environment_name = VALUES(environment_name);
