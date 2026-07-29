package com.khan366kos.lis.client.ktor.repl

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.domain.Status
import com.khan366kos.lis.client.ktor.repl.IRepl
import com.khan366kos.lis.client.ktor.dsl.pipeline
import com.khan366kos.lis.client.ktor.pipelines.LoodsmanExit
import com.khan366kos.lis.client.ktor.pipelines.LoodsmanInit
import com.khan366kos.lis.client.ktor.pipelines.PolynomInit
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
                    // Loodsman-часть гоняем, только пока она не завершена — иначе, пока
                    // ПОЛИНОМ недоступен/логин повторяется, LoodsmanInit (в т.ч. showSettings(),
                    // у которого нет своего on{}-гейта) перезапускался бы на каждой итерации.
                    if (context.status != Status.CONNECT_CHECKOUT) {
                        LoodsmanInit.execute(context)
                    }
                    // Вход в ПОЛИНОМ — сразу после успешного логина/чекаута Loodsman, до входа
                    // в COMMAND. Гейтится на status == CONNECT_CHECKOUT && !polynomLoggedIn,
                    // поэтому безопасно вызывать каждую итерацию — до успеха Loodsman это
                    // просто no-op.
                    PolynomInit.execute(context)
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
