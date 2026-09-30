CREATE TABLE pairing_codes (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    -- V1 declared utf8mb4 without COLLATE, whose MySQL 5.7 default is
    -- utf8mb4_general_ci. Referencing string columns must use the same collation.
    device_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    code_hash CHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_pairing_codes_hash (code_hash),
    INDEX idx_pairing_codes_device (device_id),
    INDEX idx_pairing_codes_cleanup (expires_at, consumed_at),
    CONSTRAINT fk_pairing_codes_device FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE pairing_requests (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    requester_device_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    target_device_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    handled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_pairing_requests_requester (requester_device_id),
    INDEX idx_pairing_requests_target (target_device_id),
    INDEX idx_pairing_requests_status_expiry (status, expires_at),
    CONSTRAINT fk_pairing_requests_requester FOREIGN KEY (requester_device_id) REFERENCES devices(id) ON DELETE CASCADE,
    CONSTRAINT fk_pairing_requests_target FOREIGN KEY (target_device_id) REFERENCES devices(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE device_bindings (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    device_low_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    device_high_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    bound_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_device_bindings_pair (device_low_id, device_high_id),
    INDEX idx_device_bindings_low (device_low_id),
    INDEX idx_device_bindings_high (device_high_id),
    CONSTRAINT fk_device_bindings_low FOREIGN KEY (device_low_id) REFERENCES devices(id) ON DELETE CASCADE,
    CONSTRAINT fk_device_bindings_high FOREIGN KEY (device_high_id) REFERENCES devices(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
