package com.khan366kos.lis.client.ktor.repl

import com.khan366kos.lis.client.ktor.domain.MigrationContext
import com.khan366kos.lis.client.ktor.repl.commands.ExitCommand
import com.khan366kos.lis.client.ktor.repl.commands.ICommand
import com.khan366kos.lis.client.ktor.repl.commands.HelloCommand
import com.khan366kos.lis.client.ktor.repl.commands.MigrationCommand

fun defaultCommands(context: MigrationContext): Map<String, ICommand> {
    val baseCommands = listOf(
        HelloCommand(),
        ExitCommand(),
        MigrationCommand(context)
    )
    val commandMap = baseCommands.associateBy { it.name }

    return commandMap
}