package com.fantasysea.clanservice.common.exception

import org.springframework.http.HttpStatus

/**
 * 业务错误码（后端设计 §49）。
 * Phase 1 只实现 Device 模块所需的四个错误码，其余留给后续 Phase。
 */
enum class ErrorCode(
    val code: Int,
    val httpStatus: HttpStatus,
    val defaultMessage: String,
) {
    INVALID_PARAMETER(40001, HttpStatus.BAD_REQUEST, "Invalid parameter"),
    INVALID_DEVICE_TOKEN(40002, HttpStatus.UNAUTHORIZED, "Invalid device token"),
    DEVICE_NOT_FOUND(40003, HttpStatus.NOT_FOUND, "Device not found"),
    INTERNAL_ERROR(50001, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
}
