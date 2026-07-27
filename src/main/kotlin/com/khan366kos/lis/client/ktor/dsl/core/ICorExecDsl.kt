package com.khan366kos.lis.client.ktor.dsl.core

interface ICorExecDsl<T> {
    val title: String
    val description: String
    fun build(): ICorExec<T>
}