package io.kotlinsnowflake

import java.sql.Connection
import java.sql.PreparedStatement

/**
 * Prepares [sql], applies the client's statement timeout and binds [params] positionally.
 *
 * Every execution path goes through here so the timeout is applied uniformly: a SELECT
 * that can run away inside a transaction is no less dangerous than one that runs on its own.
 *
 * @param timeoutSeconds seconds before the driver cancels the statement; `0` means no limit.
 */
// A failure while binding must not leak the statement, hence the broad catch.
@Suppress("TooGenericExceptionCaught")
internal fun Connection.prepared(
    sql: String,
    params: Array<out Any?>,
    timeoutSeconds: Int,
): PreparedStatement {
    val statement = prepareStatement(sql)
    try {
        if (timeoutSeconds > 0) statement.queryTimeout = timeoutSeconds
        params.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
    } catch (failure: Throwable) {
        try {
            statement.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
    return statement
}
