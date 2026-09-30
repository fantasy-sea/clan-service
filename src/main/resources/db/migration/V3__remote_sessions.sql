CREATE TABLE remote_sessions (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    -- Keep foreign-key columns compatible with devices.id from immutable V1.
    controller_device_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    controlled_device_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    accepted_at DATETIME(6) NULL,
    connect_expires_at DATETIME(6) NULL,
    connected_at DATETIME(6) NULL,
    rejected_at DATETIME(6) NULL,
    ended_at DATETIME(6) NULL,
    failed_at DATETIME(6) NULL,
    failure_reason VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_remote_sessions_controller (controller_device_id),
    INDEX idx_remote_sessions_controlled (controlled_device_id),
    INDEX idx_remote_sessions_status_request_expiry (status, expires_at),
    INDEX idx_remote_sessions_status_connect_expiry (status, connect_expires_at),
    CONSTRAINT fk_remote_sessions_controller FOREIGN KEY (controller_device_id) REFERENCES devices(id) ON DELETE CASCADE,
    CONSTRAINT fk_remote_sessions_controlled FOREIGN KEY (controlled_device_id) REFERENCES devices(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
