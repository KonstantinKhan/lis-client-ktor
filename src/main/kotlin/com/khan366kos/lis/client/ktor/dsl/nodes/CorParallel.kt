package com.khan366kos.lis.client.ktor.dsl.nodes

import com.khan366kos.lis.client.ktor.dsl.core.AbstractWorker
import com.khan366kos.lis.client.ktor.dsl.core.ICorExec
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class CorParallel<T>(
    private val execs: List<ICorExec<T>>,
    override val blockOn: T.() -> Boolean,
    override val blockExcept: T.(e: Throwable) -> Unit

) : AbstractWorker<T>(blockOn, blockExcept) {
    override suspend fun handle(context: T): Unit = coroutineScope {
        execs
            .map {
                launch {
                    it.execute(context)
                }
            }.toList()
            .joinAll()
    }
}