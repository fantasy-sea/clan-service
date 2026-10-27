package com.fantasysea.clanservice.common.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * 方言回归护栏：MySQL 5.7 不接受 `FOR UPDATE OF <alias>`（1064），
 * 这里锁定生成的锁子句，避免升级 Hibernate 后重新引入该语法。
 */
class MySQL57DialectTest {

    private val dialect = MySQL57Dialect()

    @Test
    fun `pessimistic write lock does not use the MySQL 8 alias syntax`() {
        assertEquals(" for update", dialect.getForUpdateString())
        assertEquals(" for update", dialect.getForUpdateString("d1_0"))
        assertFalse(dialect.getForUpdateString("d1_0").contains(" of "))
    }
}
