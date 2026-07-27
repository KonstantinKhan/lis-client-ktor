package com.khan366kos.lis.client.ktor.dsl.builders

import com.khan366kos.lis.client.ktor.dsl.core.CorComponentDsl
import com.khan366kos.lis.client.ktor.dsl.core.ICorChainDsl
import com.khan366kos.lis.client.ktor.dsl.core.ICorExecDsl
import com.khan366kos.lis.client.ktor.dsl.core.ICorExec
import com.khan366kos.lis.client.ktor.dsl.nodes.CorChain

class CorChainDsl<T>(
    private val workers: MutableList<ICorExecDsl<T>> = mutableListOf(),
    override val title: String = "",
    override val description: String = "",
) : CorComponentDsl<T>(), ICorChainDsl<T> {
    override fun add(worker: ICorExecDsl<T>) {
        workers.add(worker)
    }

    override fun build(): ICorExec<T> = CorChain(
        execs = workers.map { it.build() },
        blockOn = blockOn,
        blockExcept = blockExcept
    )

}