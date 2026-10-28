package com.fantasysea.clanservice.persistence

import com.fantasysea.clanservice.common.exception.BusinessException
import com.fantasysea.clanservice.common.exception.ErrorCode
import com.fantasysea.clanservice.pairing.security.PairingCodeHasher
import com.fantasysea.clanservice.pairing.service.PairingService
import com.fantasysea.clanservice.session.service.SessionService
import com.fantasysea.clanservice.websocket.WsEventGateway
import com.fantasysea.clanservice.websocket.WsOutboundMessage
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.dao.DuplicateKeyException
import org.junit.jupiter.api.Assertions.assertThrows
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        // 测试资源里的 application.yml 不包含主配置的方言，这里显式指定：
        // Hibernate 7 默认方言会生成 MySQL 8 的 `FOR UPDATE OF`，在 5.7 上必然 1064。
        "spring.jpa.properties.hibernate.dialect=com.fantasysea.clanservice.common.persistence.MySQL57Dialect",
        "clan.websocket.container-enabled=false",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
@Import(MySql57MigrationIT.GatewayConfiguration::class)
class MySql57MigrationIT {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var pairingService: PairingService
    @Autowired private lateinit var sessionService: SessionService
    @Autowired private lateinit var pairingCodeHasher: PairingCodeHasher

    @Test
    fun `V1 through V3 migrate and Hibernate validates on MySQL 5_7`() {
        val versions = jdbc.queryForList("select version from flyway_schema_history where success = 1 order by installed_rank", String::class.java)
        assertEquals(listOf("1", "2", "3"), versions)

        val tables = jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = database()",
            String::class.java,
        ).filterNotNull().map { tableName -> tableName.lowercase() }.toSet()
        assertTrue(tables.containsAll(setOf("devices", "pairing_codes", "pairing_requests", "device_bindings", "remote_sessions")))

        val foreignKeyCollations = jdbc.queryForList(
            "select table_name, column_name, collation_name from information_schema.columns " +
                "where table_schema = database() and ((table_name = 'devices' and column_name = 'id') " +
                "or column_name in ('device_id', 'requester_device_id', 'target_device_id', " +
                "'device_low_id', 'device_high_id', 'controller_device_id', 'controlled_device_id'))",
        )
        assertTrue(foreignKeyCollations.isNotEmpty())
        assertEquals(1, foreignKeyCollations.map { it["COLLATION_NAME"] }.toSet().size)
    }

    @Test
    fun `HMAC uniqueness and device cascades are enforced by MySQL`() {
        insertDevice("dev_it_a", "token-hash-it-a")
        insertDevice("dev_it_b", "token-hash-it-b")
        val hash = "a".repeat(64)
        jdbc.update(
            "insert into pairing_codes(device_id, code_hash, expires_at, created_at) values (?, ?, current_timestamp(6), current_timestamp(6))",
            "dev_it_a", hash,
        )
        assertThrows(DuplicateKeyException::class.java) {
            jdbc.update(
                "insert into pairing_codes(device_id, code_hash, expires_at, created_at) values (?, ?, current_timestamp(6), current_timestamp(6))",
                "dev_it_b", hash,
            )
        }
        jdbc.update(
            "insert into device_bindings(device_low_id, device_high_id, bound_at) values (?, ?, current_timestamp(6))",
            "dev_it_a", "dev_it_b",
        )
        jdbc.update(
            "insert into pairing_requests(id, requester_device_id, target_device_id, status, expires_at, created_at) " +
                "values ('pairreq_it', 'dev_it_a', 'dev_it_b', 'PENDING', current_timestamp(6), current_timestamp(6))",
        )
        jdbc.update(
            "insert into remote_sessions(id, controller_device_id, controlled_device_id, status, expires_at, created_at) " +
                "values ('session_it', 'dev_it_a', 'dev_it_b', 'REQUESTING', current_timestamp(6), current_timestamp(6))",
        )

        jdbc.update("delete from devices where id = 'dev_it_a'")

        assertEquals(0, jdbc.queryForObject("select count(*) from pairing_codes where device_id = 'dev_it_a'", Int::class.java))
        assertEquals(0, jdbc.queryForObject("select count(*) from device_bindings where device_low_id = 'dev_it_a'", Int::class.java))
        assertEquals(0, jdbc.queryForObject("select count(*) from pairing_requests where id = 'pairreq_it'", Int::class.java))
        assertEquals(0, jdbc.queryForObject("select count(*) from remote_sessions where id = 'session_it'", Int::class.java))
    }

    @Test
    fun `create pairing code locks the device row on MySQL 5_7`() {
        val suffix = System.nanoTime().toString()
        val deviceId = "dev_code_$suffix"
        insertDevice(deviceId, "token-code-$suffix")

        // createCode 的第一步就是 DeviceLockService.lock(deviceId)，即
        // `select ... from devices where id=? for update`；方言不兼容时这里会 1064。
        val response = pairingService.createCode(deviceId)

        assertTrue(response.pairingCode.matches(Regex("^[0-9]{6}$")))
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from pairing_codes where device_id = ? and consumed_at is null",
                Int::class.java,
                deviceId,
            ),
        )
    }

    @Test
    fun `one pairing code can be consumed atomically only once`() {
        val suffix = System.nanoTime().toString()
        val target = "dev_pair_target_$suffix"
        val first = "dev_pair_first_$suffix"
        val second = "dev_pair_second_$suffix"
        insertDevice(target, "token-target-$suffix")
        insertDevice(first, "token-first-$suffix")
        insertDevice(second, "token-second-$suffix")
        val rawCode = "654321"
        val codeHash = pairingCodeHasher.hash(rawCode)
        jdbc.update(
            "insert into pairing_codes(device_id, code_hash, expires_at, created_at) values (?, ?, ?, ?)",
            target, codeHash, Timestamp.from(Instant.now().plusSeconds(60)), Timestamp.from(Instant.now()),
        )

        val outcomes = runConcurrently(
            { pairingService.verify(first, rawCode) },
            { pairingService.verify(second, rawCode) },
        )

        assertEquals(1, outcomes.count { it == null })
        assertEquals(
            1,
            outcomes.filterIsInstance<BusinessException>().count { it.errorCode == ErrorCode.PAIRING_CODE_USED },
        )
        assertEquals(1, jdbc.queryForObject("select count(*) from pairing_requests where target_device_id = ?", Int::class.java, target))
    }

    @Test
    fun `opposite concurrent session requests create only one active session`() {
        val suffix = System.nanoTime().toString()
        val first = "dev_session_a_$suffix"
        val second = "dev_session_b_$suffix"
        insertDevice(first, "session-token-a-$suffix")
        insertDevice(second, "session-token-b-$suffix")
        val (low, high) = if (first < second) first to second else second to first
        jdbc.update(
            "insert into device_bindings(device_low_id, device_high_id, bound_at) values (?, ?, current_timestamp(6))",
            low, high,
        )

        val outcomes = runConcurrently(
            { sessionService.create(first, second) },
            { sessionService.create(second, first) },
        )

        assertEquals(1, outcomes.count { it == null })
        assertEquals(
            1,
            outcomes.filterIsInstance<BusinessException>().count { it.errorCode == ErrorCode.SESSION_CONFLICT },
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from remote_sessions where status in ('REQUESTING', 'CONNECTING', 'CONNECTED') " +
                    "and (controller_device_id in (?, ?) or controlled_device_id in (?, ?))",
                Int::class.java, first, second, first, second,
            ),
        )
    }

    @Test
    fun `session reaches connected and persists on MySQL 5_7`() {
        val suffix = System.nanoTime().toString()
        val controller = "dev_connect_a_$suffix"
        val controlled = "dev_connect_b_$suffix"
        insertDevice(controller, "connect-token-a-$suffix")
        insertDevice(controlled, "connect-token-b-$suffix")
        val (low, high) = if (controller < controlled) controller to controlled else controlled to controller
        jdbc.update(
            "insert into device_bindings(device_low_id, device_high_id, bound_at) values (?, ?, current_timestamp(6))",
            low, high,
        )

        val session = sessionService.create(controller, controlled)
        sessionService.accept(controlled, session.sessionId)
        val connected = sessionService.connect(controller, session.sessionId)

        assertEquals("CONNECTED", connected.status)
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from remote_sessions where id = ? and status = 'CONNECTED' and connected_at is not null",
                Int::class.java,
                session.sessionId,
            ),
        )
    }

    private fun runConcurrently(first: () -> Any, second: () -> Any): List<Throwable?> {
        val executor = Executors.newFixedThreadPool(2)
        return try {
            val start = CountDownLatch(1)
            val futures = listOf(first, second).map { action ->
                executor.submit<Throwable?> {
                    start.await()
                    try {
                        action()
                        null
                    } catch (failure: Throwable) {
                        failure
                    }
                }
            }
            start.countDown()
            futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun insertDevice(id: String, tokenHash: String) {
        jdbc.update(
            "insert into devices(id, device_name, platform, device_token_hash, online, created_at, updated_at) " +
                "values (?, 'integration phone', 'ANDROID', ?, false, current_timestamp(6), current_timestamp(6))",
            id, tokenHash,
        )
    }

    companion object {
        @Container
        @JvmField
        val mysql = MySQLContainer<Nothing>("mysql:5.7.44").apply {
            withDatabaseName("clan_service")
            withUsername("clan")
            withPassword("clan-test-password")
        }

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl)
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class GatewayConfiguration {
        @Bean
        @Primary
        fun alwaysReachableGateway(): WsEventGateway = object : WsEventGateway {
            override fun isReachable(deviceId: String) = true
            override fun send(deviceId: String, message: WsOutboundMessage) = true
            override fun closeDevice(deviceId: String) = Unit
        }
    }
}
