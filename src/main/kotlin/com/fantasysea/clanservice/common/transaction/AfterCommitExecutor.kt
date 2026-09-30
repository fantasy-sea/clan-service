package com.fantasysea.clanservice.common.transaction

import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Component
class AfterCommitExecutor {
    fun execute(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    }
}
