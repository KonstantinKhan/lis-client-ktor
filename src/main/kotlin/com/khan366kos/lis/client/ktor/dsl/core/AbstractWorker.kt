package com.khan366kos.lis.client.ktor.dsl.core

abstract class AbstractWorker<T>(
    open val blockOn: T.() -> Boolean,
    open val blockExcept: T.(e: Throwable) -> Unit
) : ICorWorker<T> {
    override suspend fun on(context: T): Boolean = blockOn(context)
    override suspend fun except(context: T, e: Throwable) = blockExcept(context, e)
}