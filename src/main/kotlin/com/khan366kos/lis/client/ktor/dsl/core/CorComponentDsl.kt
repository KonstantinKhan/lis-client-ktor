package com.khan366kos.lis.client.ktor.dsl.core

@CorDsl
abstract class CorComponentDsl<T>(
    var blockOn: T.() -> Boolean = { true },
    var blockExcept: T.(e: Throwable) -> Unit = { }
) : ICorExecDsl<T> {
    fun on(function: T.() -> Boolean) { blockOn = function }
    fun except(function: T.(e: Throwable) -> Unit) { blockExcept = function }
}