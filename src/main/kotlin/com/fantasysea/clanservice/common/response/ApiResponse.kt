package com.fantasysea.clanservice.common.response

/**
 * 统一 REST 响应信封：{ code, message, data }。
 * code = 0 表示成功；失败时 data 序列化为 null。
 */
data class ApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T? = null,
) {
    companion object {
        fun <T> success(data: T? = null): ApiResponse<T> =
            ApiResponse(code = 0, message = "success", data = data)

        fun error(code: Int, message: String): ApiResponse<Nothing> =
            ApiResponse(code = code, message = message, data = null)
    }
}
