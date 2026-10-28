package com.fantasysea.clanservice.common.validation

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import tools.jackson.databind.JsonNode

/**
 * P4 WebSocket payload 的统一字段校验。
 *
 * 错误文案只描述字段和期望，不回显客户端传入的值，避免把 SDP/ICE/控制坐标写进
 * ERROR 响应或日志。
 */
private fun invalid(field: String, expectation: String): Nothing =
    throw BusinessException(ErrorCode.INVALID_PARAMETER, "Invalid $field: $expectation")

fun JsonNode.requiredText(field: String, maxBytes: Int, allowBlank: Boolean = false): String {
    val node = path(field)
    if (!node.isString) invalid(field, "expected string")
    val value = node.asText()
    if (!allowBlank && value.isBlank()) invalid(field, "must not be blank")
    if (value.toByteArray(Charsets.UTF_8).size > maxBytes) invalid(field, "too long")
    return value
}

fun JsonNode.optionalText(field: String, maxBytes: Int, allowBlank: Boolean = true): String? {
    val node = path(field)
    if (node.isMissingNode || node.isNull) return null
    if (!node.isString) invalid(field, "expected string")
    val value = node.asText()
    if (!allowBlank && value.isBlank()) invalid(field, "must not be blank")
    if (value.toByteArray(Charsets.UTF_8).size > maxBytes) invalid(field, "too long")
    return value
}

fun JsonNode.requiredFiniteDouble(field: String, range: ClosedFloatingPointRange<Double>): Double {
    val node = path(field)
    if (!node.isNumber) invalid(field, "expected number")
    val value = node.doubleValue()
    if (!value.isFinite() || value !in range) invalid(field, "out of range")
    return value
}

fun JsonNode.requiredInt(field: String, range: IntRange): Int {
    val node = path(field)
    if (!node.isIntegralNumber) invalid(field, "expected integer")
    val value = node.longValue()
    if (value < range.first || value > range.last) invalid(field, "out of range")
    return value.toInt()
}

fun JsonNode.optionalInt(field: String, range: IntRange): Int? {
    val node = path(field)
    if (node.isMissingNode || node.isNull) return null
    if (!node.isIntegralNumber) invalid(field, "expected integer")
    val value = node.longValue()
    if (value < range.first || value > range.last) invalid(field, "out of range")
    return value.toInt()
}

fun JsonNode.requiredBoolean(field: String): Boolean {
    val node = path(field)
    if (!node.isBoolean) invalid(field, "expected boolean")
    return node.booleanValue()
}
