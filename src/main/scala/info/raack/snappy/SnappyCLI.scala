/**
 * This file is part of Snappy.
 *
 * (C) Copyright 2016 Taylor Raack.
 *
 * Snappy is free software: you can redistribute it and/or modify
 * it under the terms of the Affero GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Snappy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Affero GNU General Public License for more details.
 *
 * You should have received a copy of the Affero GNU General Public License
 * along with Snappy.  If not, see <http://www.gnu.org/licenses/>.
 */

package info.raack.snappy

import scala.io.Source
import scala.util.Failure
import scala.util.Success
import scala.util.Try

object SnappyCLI extends App {

  args.headOption.map(process(_).getOrElse(0)).getOrElse({
    // wait for commands
    Source.stdin.getLines.map(process(_)).collectFirst({ case Some(x) => x }).getOrElse(0)
  }) match {
    case 0 => ()
    case other => sys.exit(other)
  }

  private def process(command: String): Option[Int] = {
    command match {
      case "scheduled-backup" => handleBackupOutput(new Snappy().scheduledBackup())
      case "schedule-on" => { new Snappy().schedule(true); None }
      case "schedule-off" => { new Snappy().schedule(false); None }
      case "backup" => handleBackupOutput(new Snappy().backup())
      case "help" => { println(help); None }
      case "exit" => { Some(0) }
      case other => { println(s"$other is not a valid command.\n${help}"); None }
    }
  }

  private def handleBackupOutput(output: Try[String]): Option[Int] = {
    output match {
      case Success(message) => { println(s"Succeeded with message: $message"); None }
      case Failure(e) => { println(s"Could not backup: ${e.getMessage}"); None }
    }
  }

  private def help(): String = {
    """
Usage: snappy [COMMAND]
Run Snappy command specified as COMMAND, or enter the Snappy shell if COMMAND is not specified.

Possible values for COMMAND
  backup                     complete a backup using the current settings
  help                       print this help
"""
  }
}
