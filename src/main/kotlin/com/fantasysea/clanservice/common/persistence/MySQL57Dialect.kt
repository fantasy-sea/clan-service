package com.fantasysea.clanservice.common.persistence

import org.hibernate.dialect.MySQLDialect

/**
 * MySQL 5.7 兼容方言。
 *
 * Hibernate ORM 7 的 [MySQLDialect] 按 MySQL 8.0 生成带别名限定的悲观锁语法：
 * `select ... where id = ? for update of <alias>`。MySQL 5.7 不支持 `FOR UPDATE OF`，
 * 会直接报 1064（`... error in your SQL syntax ... near 'of d1_0'`），于是所有
 * `@Lock(PESSIMISTIC_WRITE)` 查询全部失败：
 * - `POST /api/v1/pairing/codes` 里的 `DeviceLockService.lock` 抛异常 → 500；
 * - WebSocket AUTH 里的 `PresenceService.connected` 抛异常 → 不回 AUTH_SUCCESS，
 *   客户端只能等到认证超时。
 *
 * 关掉别名锁后 Hibernate 回退到 MySQL 5.7 与 8.0 都支持的 `for update`，读锁回退到
 * `lock in share mode`。本项目的锁查询都只有单个别名，去掉 `OF <alias>` 不会改变加锁范围。
 *
 * 注意：方言通过类名显式注册（见 `application.yml`），因此 Hibernate 不再做版本探测，
 * 内部版本按 8.0 计算。项目未使用依赖版本的其他特性（无 JSON、窗口函数、skip locked /
 * nowait 锁超时；schema 由 Flyway 管理），升级到 MySQL 8 后本方言依然可用。
 */
class MySQL57Dialect : MySQLDialect() {

    /** MySQL 5.7 不支持 {@code FOR UPDATE OF <alias>}，回退为 {@code FOR UPDATE}。 */
    override fun supportsAliasLocks(): Boolean = false

    /** MySQL 5.7 的共享锁写法是 {@code LOCK IN SHARE MODE}，没有 {@code FOR SHARE}。 */
    override fun supportsForShare(): Boolean = false
}
