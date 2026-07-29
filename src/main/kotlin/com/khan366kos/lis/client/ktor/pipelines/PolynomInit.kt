package com.khan366kos.lis.client.ktor.pipelines

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.dsl.core.ICorExec
import com.khan366kos.lis.client.ktor.dsl.pipeline
import com.khan366kos.lis.client.ktor.workers.polynomLogin

object PolynomInit : ICorExec<MigrationContext> by pipeline({
    polynomLogin()
}).build()
