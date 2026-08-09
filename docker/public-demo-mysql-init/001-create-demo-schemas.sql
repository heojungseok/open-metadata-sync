CREATE DATABASE IF NOT EXISTS open_metadata_benchmark_preflight
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

GRANT ALL PRIVILEGES ON open_metadata.* TO 'open_metadata'@'%';
GRANT ALL PRIVILEGES ON open_metadata_benchmark_preflight.* TO 'open_metadata'@'%';
