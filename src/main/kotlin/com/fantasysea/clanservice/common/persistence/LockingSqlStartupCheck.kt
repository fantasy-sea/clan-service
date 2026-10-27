package com.fantasysea.clanservice.common.persistence

import com.fantasysea.clanservice.device.repository.DeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * 启动自检：实际执行一次悲观锁查询，确认当前数据库接受 Hibernate 生成的锁语法。
 *
 * 背景：Hibernate ORM 7 会按 MySQL 8 生成 `FOR UPDATE OF <alias>`，连到 MySQL 5.7 时
 * 只有在第一次配对/会话请求才会暴露 1064，启动阶段一切正常。这里用一次命中不到行的
 * 锁查询（不会真的锁住任何行）把问题提前打到启动日志，并明确指向方言配置。
 *
 * 只记录错误、不阻断启动：数据库偶发抖动不应该让服务起不来，但日志必须足以定位。
 */
@Component
class LockingSqlStartupCheck(
    private val transactions: TransactionTemplate,
    private val deviceRepository: DeviceRepository,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(LockingSqlStartupCheck::class.java)

    override fun run(args: ApplicationArguments) {
        try {
            transactions.executeWithoutResult {
                deviceRepository.findByIdForUpdate(PROBE_DEVICE_ID)
            }
            log.info("Pessimistic-lock SQL check passed (dialect is compatible with this database)")
        } catch (ex: Exception) {
            log.error(
                "Pessimistic-lock SQL is rejected by the database. " +
                    "Check spring.jpa.properties.hibernate.dialect " +
                    "(MySQL 5.7 needs com.fantasysea.clanservice.common.persistence.MySQL57Dialect); " +
                    "pairing and session APIs will fail until this is fixed.",
                ex,
            )
        }
    }

    private companion object {
        /** 不可能存在的设备 id：查不到行，只验证 SQL 语法，不锁真实数据。 */
        const val PROBE_DEVICE_ID = "__startup_lock_probe__"
    }
}
