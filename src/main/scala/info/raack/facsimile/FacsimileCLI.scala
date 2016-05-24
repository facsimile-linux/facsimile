/**
 * This file is part of Facsimile.
 *
 * (C) Copyright 2016 Taylor Raack.
 *
 * Facsimile is free software: you can redistribute it and/or modify
 * it under the terms of the Affero GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Facsimile is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Affero GNU General Public License for more details.
 *
 * You should have received a copy of the Affero GNU General Public License
 * along with Facsimile.  If not, see <http://www.gnu.org/licenses/>.
 */

package info.raack.facsimile

import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.io.Source
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.google.gson.Gson

object FacsimileCLI extends App {

  // TODO - make sure that facsimile-toolbar.desktop is installed in /etc/xdg/autostart

  args.headOption.map(process(_).getOrElse(0)).getOrElse({
    // wait for commands
    print("Welcome to Facsimile!\n> ")
    Source.stdin.getLines.map(x => { val o = process(x); if (o == None) { print("> ") }; o }).collectFirst({ case Some(x) => x }).getOrElse(0)
  }) match {
    case 0 => ()
    case other => sys.exit(other)
  }

  private def process(command: String): Option[Int] = {
    command match {
      case "scheduled-backup" => handleBackupOutput(new Facsimile().scheduledBackup())
      case "schedule-on" => { new Facsimile().schedule(true); None }
      case "schedule-off" => { new Facsimile().schedule(false); None }
      case "backup" => handleBackupOutput(new Facsimile().backup())
      case "list-snapshots" => { println(new Gson().toJson(new Facsimile().snapshots().toArray)); None }
      case "get-configuration" => { println(new Gson().toJson(new Facsimile().configuration().asJava)); None }
      case "help" => { println(help); None }
      case "exit" => Some(0)
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
Usage: facsimile [COMMAND]
Run Facsimile command specified as COMMAND, or enter the Facsimile shell if COMMAND is not specified.

Possible values for COMMAND
  backup                     complete a backup using the current settings
  schedule-on                turn on scheduled backups
  schedule-off               turn off scheduled backups
  scheduled-backup           if a backup is required (schedule is on and enough time has past so backups are less than 25% of wall time)
  help                       print this help
  exit                       exit Facsimile
"""
  }
}
