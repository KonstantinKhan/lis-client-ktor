package com.khan366kos.lis.client.ktor.pipelines

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.dsl.core.ICorExec
import com.khan366kos.lis.client.ktor.dsl.pipeline
import com.khan366kos.lis.client.ktor.workers.checkConfig
import com.khan366kos.lis.client.ktor.workers.readConfig

object PreparePipeline : ICorExec<MigrationContext> by pipeline({
    checkConfig()
    readConfig()
}).build()