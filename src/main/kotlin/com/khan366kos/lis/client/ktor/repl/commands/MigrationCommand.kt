package com.khan366kos.lis.client.ktor.repl.commands

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.pipelines.MigrationPipeline
import com.khan366kos.lis.client.ktor.repl.Result

class MigrationCommand(
    val context: MigrationContext
) : ICommand {
    override val name: String = "migration"
    override suspend fun execute(args: String): Result = try {
        MigrationPipeline.execute(context)
        Result.Show("Миграция завершена: объектов ${context.identifiers.size}")
    } catch (e: Exception) {
        Result.Show("Миграция прервана: ${e.message}")
    }
}
