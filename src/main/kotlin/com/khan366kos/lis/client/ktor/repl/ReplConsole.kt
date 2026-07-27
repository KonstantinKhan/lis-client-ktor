package com.khan366kos.lis.client.ktor.repl

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.repl.IRepl
import com.khan366kos.lis.client.ktor.dsl.pipeline
import com.khan366kos.lis.client.ktor.pipelines.LoodsmanExit
import com.khan366kos.lis.client.ktor.pipelines.LoodsmanInit
import com.khan366kos.lis.client.ktor.pipelines.PreparePipeline
import com.khan366kos.lis.client.ktor.repl.commands.ICommand
import com.khan366kos.lis.client.ktor.workers.checkin

class ReplConsole(
    private val context: MigrationContext = MigrationContext(),
    private val prompt: String = ">>> ",
    private val commands: Map<String, ICommand> = defaultCommands(context)
) : IRepl {
    suspend fun start() {
        println("=== Утилита миграции ЭСИ ===")
        println()

        while (true) {
            when (context.replStatus) {
                ReplStatus.START -> {
                    PreparePipeline.execute(context)
                    continue
                }

                ReplStatus.AUTH -> {
                    LoodsmanInit.execute(context)
                    continue
                }

                ReplStatus.AUTHORIZED -> {

                }

                ReplStatus.COMMAND -> {
                    print(prompt)

                    val input = readlnOrNull()?.trim() ?: break
                    if (input.isEmpty()) continue

                    val parts = input.split(" ", limit = 2)

                    val commandName = parts[0]
                    val args = parts.getOrElse(1) { "" }

                    val command = commands[commandName]

                    val result = command?.execute(args) ?: Result.Show("Unknown command: $commandName")

                    when (result) {
                        is Result.Show -> println(result.message)
                        is Result.Exit -> {
                            LoodsmanExit.execute(context)
                            println(result.message)
                            return
                        }
                    }
                }
            }
        }
    }
}
