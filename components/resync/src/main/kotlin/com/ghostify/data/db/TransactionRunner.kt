package com.ghostify.data.db

/**
 * Abstraction over atomic multi-statement writes so the sync diff can be
 * unit-tested with fakes, while the real implementation is backed by Room/
 * SQLite transactions (`androidx.room.withTransaction`).
 *
 * Any exception thrown by [block] rolls back every statement executed so far
 * (T-067).
 */
interface TransactionRunner {
    suspend fun <R> withinTransaction(block: suspend () -> R): R
}
