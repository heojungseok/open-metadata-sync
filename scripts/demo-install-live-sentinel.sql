CREATE TABLE IF NOT EXISTS open_metadata_live_demo.demo_environment_guard (
    environment_uuid CHAR(36) PRIMARY KEY,
    environment_name VARCHAR(64) NOT NULL
);

INSERT INTO open_metadata_live_demo.demo_environment_guard (environment_uuid, environment_name)
VALUES ('00000000-0000-0000-0000-00000000d100', 'open-metadata-sync-public-live-demo')
ON DUPLICATE KEY UPDATE environment_name = VALUES(environment_name);
