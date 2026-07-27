package com.khan366kos.lis.client.ktor.dsl

import com.khan366kos.lis.client.ktor.dsl.builders.CorChainDsl
import com.khan366kos.lis.client.ktor.dsl.builders.CorWorkerDsl
import com.khan366kos.lis.client.ktor.dsl.core.ICorChainDsl
import kotlin.apply

fun <T> pipeline(block: CorChainDsl<T>.() -> Unit) = CorChainDsl<T>().apply(block)

fun <T> ICorChainDsl<T>.worker(block: CorWorkerDsl<T>.() -> Unit) {
    add(CorWorkerDsl<T>().apply(block))
}

fun <T> ICorChainDsl<T>.parallel(block: CorWorkerDsl<T>.() -> Unit) {
    add(CorWorkerDsl<T>().apply(block))
}

fun <T> ICorChainDsl<T>.pipeline(block: CorChainDsl<T>.() -> Unit) {
    add(CorChainDsl<T>().apply(block))
}
