package com.fantasysea.clanservice.common.exception

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders

class GlobalExceptionHandlerSecurityTest {
    @Test
    fun `unexpected exception does not leak its secret message to logs`() {
        val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            GlobalExceptionHandler().handleUnexpected(RuntimeException("secret-marker-never-log"))
        } finally {
            logger.detachAppender(appender)
        }

        assertFalse(appender.list.any { it.formattedMessage.contains("secret-marker-never-log") })
    }

    @Test
    fun `rate limit error includes HTTP retry after`() {
        val response = GlobalExceptionHandler().handleBusinessException(
            BusinessException(ErrorCode.RATE_LIMITED, retryAfterSeconds = 17),
        )

        assertEquals("17", response.headers.getFirst(HttpHeaders.RETRY_AFTER))
        assertEquals(429, response.statusCode.value())
        assertEquals(40005, response.body?.code)
    }
}
