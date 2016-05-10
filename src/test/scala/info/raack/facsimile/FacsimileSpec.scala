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

import java.io.ByteArrayOutputStream
import java.io.PrintStream

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

import scala.sys.process.stringToProcess

import org.scalatest.FeatureSpec
import org.scalatest.GivenWhenThen

class FacsimileSpec extends FeatureSpec with GivenWhenThen {

  info("As a Facsimile user")
  info("I want to be run backups")
  info("So I can ensure that my data is safe in case of data loss")

  feature("Facsimile") {
    scenario("User requests help") {

      Given("a command line instance is available")

      When("the help command is given")
      val baos = new ByteArrayOutputStream
      val ps = new PrintStream(baos)
      Console.withOut(ps)(Console.withErr(ps)(FacsimileCLI.main(Array("help"))))

      Then("help should be printed out")
      val help = """
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

      assertResult(help) { baos.toString }
    }
  }
}
