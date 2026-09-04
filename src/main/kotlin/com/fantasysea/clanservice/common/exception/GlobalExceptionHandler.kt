package com.fantasysea.clanservice.common.exception

import com.fantasysea.clanservice.common.response.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(ex: BusinessException): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity
            .status(ex.errorCode.httpStatus)
            .body(ApiResponse.error(ex.errorCode.code, ex.message ?: ex.errorCode.defaultMessage))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(ex: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Nothing>> {
        val detail = ex.bindingResult.fieldErrors.firstOrNull()?.let { error ->
            error.field + ": " + (error.defaultMessage ?: "invalid")
        } ?: ErrorCode.INVALID_PARAMETER.defaultMessage
        return ResponseEntity
            .status(ErrorCode.INVALID_PARAMETER.httpStatus)
            .body(ApiResponse.error(ErrorCode.INVALID_PARAMETER.code, detail))
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleMethodValidation(ex: HandlerMethodValidationException): ResponseEntity<ApiResponse<Nothing>> {
        val detail = ex.allErrors.firstOrNull()?.defaultMessage ?: ErrorCode.INVALID_PARAMETER.defaultMessage
        return ResponseEntity
            .status(ErrorCode.INVALID_PARAMETER.httpStatus)
            .body(ApiResponse.error(ErrorCode.INVALID_PARAMETER.code, detail))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(ex: HttpMessageNotReadableException): ResponseEntity<ApiResponse<Nothing>> {
        log.debug("Malformed request body", ex)
        return ResponseEntity
            .status(ErrorCode.INVALID_PARAMETER.httpStatus)
            .body(ApiResponse.error(ErrorCode.INVALID_PARAMETER.code, "Malformed request body"))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.error("Unexpected error", ex)
        return ResponseEntity
            .status(ErrorCode.INTERNAL_ERROR.httpStatus)
            .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR.code, ErrorCode.INTERNAL_ERROR.defaultMessage))
    }
}
