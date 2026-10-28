package com.fantasysea.clanservice.control.dto

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.common.validation.optionalText
import com.fantasysea.clanservice.common.validation.requiredBoolean
import com.fantasysea.clanservice.common.validation.requiredFiniteDouble
import com.fantasysea.clanservice.common.validation.requiredInt
import com.fantasysea.clanservice.common.validation.requiredText
import tools.jackson.databind.JsonNode

enum class ControlAction { TAP, SWIPE, BACK, HOME, RECENTS }

/**
 * 归一化后的控制指令。坐标统一为 0.0~1.0，被控端负责映射到实际屏幕像素。
 * 服务端只转发已知字段，避免把客户端附加数据透传给被控端。
 */
data class ControlCommand(
    val action: ControlAction,
    val x: Double? = null,
    val y: Double? = null,
    val startX: Double? = null,
    val startY: Double? = null,
    val endX: Double? = null,
    val endY: Double? = null,
    val duration: Int? = null,
) {
    fun toPayload(): Map<String, Any> = when (action) {
        ControlAction.TAP -> mapOf("action" to action.name, "x" to requireNotNull(x), "y" to requireNotNull(y))
        ControlAction.SWIPE -> mapOf(
            "action" to action.name,
            "startX" to requireNotNull(startX),
            "startY" to requireNotNull(startY),
            "endX" to requireNotNull(endX),
            "endY" to requireNotNull(endY),
            "duration" to requireNotNull(duration),
        )
        ControlAction.BACK, ControlAction.HOME, ControlAction.RECENTS -> mapOf("action" to action.name)
    }

    companion object {
        private const val MAX_ACTION_BYTES = 16
        private val COORDINATE = 0.0..1.0
        private val DURATION = 50..5000

        fun parse(payload: JsonNode): ControlCommand {
            val actionName = payload.requiredText("action", MAX_ACTION_BYTES)
            val action = ControlAction.entries.firstOrNull { it.name == actionName }
                ?: throw BusinessException(ErrorCode.INVALID_PARAMETER, "Invalid action")
            return when (action) {
                ControlAction.TAP -> ControlCommand(
                    action = action,
                    x = payload.requiredFiniteDouble("x", COORDINATE),
                    y = payload.requiredFiniteDouble("y", COORDINATE),
                )
                ControlAction.SWIPE -> ControlCommand(
                    action = action,
                    startX = payload.requiredFiniteDouble("startX", COORDINATE),
                    startY = payload.requiredFiniteDouble("startY", COORDINATE),
                    endX = payload.requiredFiniteDouble("endX", COORDINATE),
                    endY = payload.requiredFiniteDouble("endY", COORDINATE),
                    duration = payload.requiredInt("duration", DURATION),
                )
                ControlAction.BACK, ControlAction.HOME, ControlAction.RECENTS -> ControlCommand(action)
            }
        }
    }
}

enum class ControlResultStatus { OK, FAILED }

/**
 * 被控端执行结果。复用 CONTROL_RESULT，但 scope 固定为 COMMAND，
 * 与 SESSION_REQUEST 阶段的 CONTROL_RESULT 区分。
 */
data class ControlResultPayload(
    val result: ControlResultStatus,
    val reason: String? = null,
) {
    fun toPayload(): Map<String, Any> = buildMap {
        put("scope", SCOPE)
        put("result", result.name)
        if (reason != null) put("reason", reason)
    }

    companion object {
        const val SCOPE = "COMMAND"
        private const val MAX_SCOPE_BYTES = 16
        private const val MAX_RESULT_BYTES = 16
        private const val MAX_REASON_BYTES = 64

        fun parse(payload: JsonNode): ControlResultPayload {
            if (payload.requiredText("scope", MAX_SCOPE_BYTES) != SCOPE) {
                throw BusinessException(ErrorCode.INVALID_PARAMETER, "Invalid scope")
            }
            val resultName = payload.requiredText("result", MAX_RESULT_BYTES)
            val result = ControlResultStatus.entries.firstOrNull { it.name == resultName }
                ?: throw BusinessException(ErrorCode.INVALID_PARAMETER, "Invalid result")
            return ControlResultPayload(result, payload.optionalText("reason", MAX_REASON_BYTES, allowBlank = false))
        }
    }
}

data class PermissionStatusPayload(
    val screenCapture: Boolean,
    val accessibility: Boolean,
) {
    fun toPayload(): Map<String, Any> = mapOf(
        "screenCapture" to screenCapture,
        "accessibility" to accessibility,
    )

    companion object {
        fun parse(payload: JsonNode): PermissionStatusPayload = PermissionStatusPayload(
            screenCapture = payload.requiredBoolean("screenCapture"),
            accessibility = payload.requiredBoolean("accessibility"),
        )
    }
}
