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
import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.io.Source
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.json4s.NoTypeHints
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.write

object FacsimileCLI extends App {

  val facsimile = new Facsimile()

  sys.exit(args.mkString(" ") match {
    case "" => {
      // wait for commands
      print("Welcome to Facsimile!\n> ")
      Source.stdin.getLines.map(x => { val o = process(x); if (o == None) { print("> ") }; o }).collectFirst({ case Some(x) => x }).getOrElse(0)
    }
    case command => process(command).getOrElse(0)
  })

  private def process(command: String): Option[Int] = {
    implicit val formats = Serialization.formats(NoTypeHints)
    val listSnapshotFiles = """list-snapshot-files\s+(\S+)\s+(\S+)""".r
    val restoreSnapshotFiles = """restore-snapshot-files\s+(\S+)\s+(\S+)\s+(\S+)""".r
    command match {
      case "scheduled-backup" => handleBackupOutput(facsimile.scheduledBackup())
      case "schedule-on" => { facsimile.schedule(true); None }
      case "schedule-off" => { facsimile.schedule(false); None }
      case "backup" => handleBackupOutput(facsimile.backup())
      case "list-snapshots" => { println(write(facsimile.snapshots())); None }
      case listSnapshotFiles(snapshot, dir) => { println(write(facsimile.getSnapshotFiles(snapshot, dir))); None }
      case restoreSnapshotFiles(snapshot, backupPath, restorePath) => { handleBackupOutput(facsimile.restoreSnapshotFiles(snapshot, backupPath, restorePath)) }
      case "get-configuration" => { println(write(facsimile.configuration())); None }
      case "set-configuration" => { facsimile.configuration(parse(Source.stdin.getLines.mkString("")).extract[Configuration]); None }
      case "help" => { println(help); None }
      case "exit" => Some(0)
      case other => { println(s"$other is not a valid command.\n${help}"); None }
    }
  }

  private def handleBackupOutput(output: Try[Unit]): Option[Int] = {
    output match {
      case Success(x) => { println(s"Succeeded."); None }
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
