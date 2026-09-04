-- Phase 1: devices table only.
-- pairing_codes / device_bindings / remote_sessions belong to later migrations.

CREATE TABLE devices (
    id VARCHAR(64) NOT NULL PRIMARY KEY,

    device_name VARCHAR(128) NOT NULL,

    platform VARCHAR(32) NOT NULL DEFAULT 'ANDROID',

    model VARCHAR(128) NULL,

    android_version VARCHAR(64) NULL,

    -- Only SHA-256(deviceToken); the raw token is never stored.
    device_token_hash VARCHAR(128) NOT NULL,

    online BOOLEAN NOT NULL DEFAULT FALSE,

    last_seen_at DATETIME(6) NULL,

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    UNIQUE KEY uk_device_token_hash (device_token_hash),

    INDEX idx_devices_online (online)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
