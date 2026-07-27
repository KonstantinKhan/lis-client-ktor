package com.khan366kos.lis.client.ktor.dsl.nodes

import com.khan366kos.lis.client.ktor.dsl.core.AbstractWorker

class CorWorker<T>(
    override val blockOn: T.() -> Boolean,
    override val blockExcept: T.(e: Throwable) -> Unit,
    private val blockHandle: suspend T.() -> Unit
) : AbstractWorker<T>(blockOn, blockExcept) {
    override suspend fun handle(context: T) {
        blockHandle(context)
    }
}