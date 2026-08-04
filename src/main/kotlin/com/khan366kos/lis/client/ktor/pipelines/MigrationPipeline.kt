package com.khan366kos.lis.client.ktor.pipelines

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.dsl.core.ICorExec
import com.khan366kos.lis.client.ktor.dsl.pipeline
import com.khan366kos.lis.client.ktor.workers.migrateAuxMaterials
import com.khan366kos.lis.client.ktor.workers.migrateBlanks
import com.khan366kos.lis.client.ktor.workers.migrateBomMaterials
import com.khan366kos.lis.client.ktor.workers.migrateCastingBlanks
import com.khan366kos.lis.client.ktor.workers.migrateLinks
import com.khan366kos.lis.client.ktor.workers.migrateMaterials
import com.khan366kos.lis.client.ktor.workers.migrateObjects
import com.khan366kos.lis.client.ktor.workers.validateMapping

object MigrationPipeline : ICorExec<MigrationContext> by pipeline<MigrationContext>({
    validateMapping()
    migrateObjects()
    migrateLinks()
    migrateMaterials()
    migrateBomMaterials()
    migrateBlanks()
    migrateCastingBlanks()
    migrateAuxMaterials()
}).build()