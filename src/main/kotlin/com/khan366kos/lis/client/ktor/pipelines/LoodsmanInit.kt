package com.khan366kos.lis.client.ktor.pipelines

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.dsl.core.ICorExec
import com.khan366kos.lis.client.ktor.dsl.pipeline
import com.khan366kos.lis.client.ktor.workers.allTypes
import com.khan366kos.lis.client.ktor.workers.checkout
import com.khan366kos.lis.client.ktor.workers.connectCheckout
import com.khan366kos.lis.client.ktor.workers.createRoot
import com.khan366kos.lis.client.ktor.workers.login
import com.khan366kos.lis.client.ktor.workers.showSettings

object LoodsmanInit : ICorExec<MigrationContext> by pipeline({
    login()
    allTypes()
    createRoot()
    checkout()
    connectCheckout()
    showSettings()
}).build()