package com.khan366kos.lis.client.ktor.dsl.core

interface ICorWorker<T> : ICorExec<T> {
    suspend fun on(context: T): Boolean
    suspend fun handle(context: T)
    suspend fun except(context: T, e: Throwable)

    override suspend fun execute(context: T) {
        try {
            if (on(context)) {
                handle(context)
            }
        } catch (e: Throwable) {
            except(context, e)
        }
    }
}