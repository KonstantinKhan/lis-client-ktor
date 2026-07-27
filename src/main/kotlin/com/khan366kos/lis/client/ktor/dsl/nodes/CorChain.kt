package com.khan366kos.lis.client.ktor.dsl.nodes

import com.khan366kos.lis.client.ktor.dsl.core.AbstractWorker
import com.khan366kos.lis.client.ktor.dsl.core.ICorExec

class CorChain<T>(
    private val execs: List<ICorExec<T>>,
    blockOn: T.() -> Boolean,
    blockExcept: T.(e: Throwable) -> Unit
) : AbstractWorker<T>(blockOn, blockExcept) {
    override suspend fun handle(context: T) {
        execs.forEach { it.execute(context) }
    }
}