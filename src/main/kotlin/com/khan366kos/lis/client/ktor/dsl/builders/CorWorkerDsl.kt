package com.khan366kos.lis.client.ktor.dsl.builders

import com.khan366kos.lis.client.ktor.dsl.core.CorComponentDsl
import com.khan366kos.lis.client.ktor.dsl.core.ICorWorkerDsl
import com.khan366kos.lis.client.ktor.dsl.core.ICorExec
import com.khan366kos.lis.client.ktor.dsl.nodes.CorWorker

class CorWorkerDsl<T>(
    override val title: String = "",
    override val description: String = "",
    private var blockHandle: suspend T.() -> Unit = {}
) : CorComponentDsl<T>(), ICorWorkerDsl<T> {
    override fun build(): ICorExec<T> = CorWorker<T>(
        blockOn = blockOn,
        blockExcept = blockExcept,
        blockHandle = blockHandle
    )

    override fun handle(function: suspend T.() -> Unit) {
        blockHandle = function
    }
}