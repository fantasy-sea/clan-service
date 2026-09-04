package com.fantasysea.clanservice.common.exception

/**
 * 业务异常：携带 ErrorCode 与可选自定义文案，由 GlobalExceptionHandler 统一转换为 ApiResponse。
 */
class BusinessException(
    val errorCode: ErrorCode,
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message ?: errorCode.defaultMessage, cause)
