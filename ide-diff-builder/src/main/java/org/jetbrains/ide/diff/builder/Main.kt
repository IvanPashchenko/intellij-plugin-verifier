/*
 * Copyright 2000-2020 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.ide.diff.builder

import com.sampullara.cli.Args
import com.sampullara.cli.Argument
import org.jetbrains.ide.diff.builder.cli.*
import kotlin.system.exitProcess

private fun usage(): Nothing {
  System.err.println(
    """
    Usage: java -jar <command> <args> [-h]
    <command> := ${AVAILABLE_COMMANDS.joinToString(separator = " | ") { it.commandName }}
    <args> depend on command. See -h (help) for details on each command.
  """.trimIndent()
  )
  exitProcess(1)
}

private val AVAILABLE_COMMANDS = listOf(
  IdeDiffCommand(),
  IdeRepositoryIndexCommand(),
  BuildIdeApiAnnotationsCommand(),
  ApiQualityCheckCommand(),
  BuildDeprecationInfoAnnotationsCommand()
)

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    usage()
  }

  val cliOptions = CliOptions()
  val freeArgs = Args.parse(cliOptions, args, false)

  if (freeArgs.isEmpty()) {
    System.err.println("Command is not specified")
    usage()
  }

  val commandName = freeArgs.first()
  val command = AVAILABLE_COMMANDS.find { it.commandName == commandName }
  if (command == null) {
    System.err.println("Unknown command: $commandName")
    usage()
  }

  if (cliOptions.help) {
    System.err.println(command.help)
    return
  }

  command.execute(args.drop(1))
}

class CliOptions {

  @set:Argument("help", alias = "h", description = "Print command help and exit")
  var help: Boolean = false

}