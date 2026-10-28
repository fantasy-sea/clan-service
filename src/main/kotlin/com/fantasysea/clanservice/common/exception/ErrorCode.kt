package com.fantasysea.clanservice.common.exception

import org.springframework.http.HttpStatus

/**
 * P0-P4 公共业务错误码；40307/40308 用于 P4 命令权限与会话连接阶段。
 */
enum class ErrorCode(
    val code: Int,
    val httpStatus: HttpStatus,
    val defaultMessage: String,
) {
    INVALID_PARAMETER(40001, HttpStatus.BAD_REQUEST, "Invalid parameter"),
    INVALID_DEVICE_TOKEN(40002, HttpStatus.UNAUTHORIZED, "Invalid device token"),
    DEVICE_NOT_FOUND(40003, HttpStatus.NOT_FOUND, "Device not found"),
    DEVICE_OFFLINE(40004, HttpStatus.CONFLICT, "Device offline"),
    RATE_LIMITED(40005, HttpStatus.TOO_MANY_REQUESTS, "Rate limited"),
    PAIRING_CODE_INVALID(40101, HttpStatus.BAD_REQUEST, "Pairing code invalid"),
    PAIRING_CODE_EXPIRED(40102, HttpStatus.GONE, "Pairing code expired"),
    PAIRING_CODE_USED(40103, HttpStatus.CONFLICT, "Pairing code used"),
    SELF_PAIRING(40104, HttpStatus.CONFLICT, "Cannot pair a device with itself"),
    PAIRING_REQUEST_NOT_FOUND(40105, HttpStatus.NOT_FOUND, "Pairing request not found"),
    PAIRING_REQUEST_EXPIRED(40106, HttpStatus.GONE, "Pairing request expired"),
    PAIRING_REQUEST_ALREADY_HANDLED(40107, HttpStatus.CONFLICT, "Pairing request already handled"),
    PAIRING_REQUEST_NOT_ALLOWED(40108, HttpStatus.FORBIDDEN, "Pairing request not allowed"),
    PAIRING_REQUEST_ALREADY_EXISTS(40109, HttpStatus.CONFLICT, "Pairing request already exists"),
    BINDING_NOT_FOUND(40201, HttpStatus.NOT_FOUND, "Binding not found"),
    BINDING_ALREADY_EXISTS(40202, HttpStatus.CONFLICT, "Binding already exists"),
    SESSION_NOT_FOUND(40301, HttpStatus.NOT_FOUND, "Session not found"),
    SESSION_INVALID_STATE(40302, HttpStatus.CONFLICT, "Session is in an invalid state"),
    SESSION_NOT_ALLOWED(40303, HttpStatus.FORBIDDEN, "Session operation not allowed"),
    SESSION_CONFLICT(40304, HttpStatus.CONFLICT, "A device already has an active session"),
    DEVICES_NOT_BOUND(40305, HttpStatus.CONFLICT, "Devices are not bound"),
    SESSION_REQUEST_EXPIRED(40306, HttpStatus.GONE, "Session request expired"),
    CONTROL_NOT_ALLOWED(40307, HttpStatus.FORBIDDEN, "Control not allowed"),
    SESSION_CONNECT_TIMEOUT(40308, HttpStatus.GONE, "Session connection timed out"),
    INTERNAL_ERROR(50001, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
}
