package xyz.botolog.ghostify.data.db

/**
 * Abstraction over atomic multi-statement writes so repository logic can be unit-tested
 * with fakes, while the real implementation is backed by Room/SQLite transactions.
 *
 * [AppDatabase.transactionRunner] implements this by delegating to `androidx.room.withTransaction`,
 * which runs [block] inside a single SQLite transaction: any exception rolls back
 * every statement executed so far.
 *
 * Note: named `withinTransaction` (not `withTransaction`) so the member cannot shadow
 * the `androidx.room.withTransaction` extension.
 */
interface TransactionRunner {
    suspend fun <R> withinTransaction(block: suspend () -> R): R
}
