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

    /**
     * Executes [block] inside a single database transaction.
     *
     * If [block] completes successfully, the transaction is committed.
     * If [block] throws, the transaction is rolled back and the exception propagates.
     *
     * @param R The return type of the transactional block.
     * @param block The suspend lambda to execute within the transaction.
     * @return The result of [block].
     */
    suspend fun <R> withinTransaction(block: suspend () -> R): R
}
