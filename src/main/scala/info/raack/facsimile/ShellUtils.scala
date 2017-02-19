/**
 * This file is part of Facsimile.
 *
 * (C) Copyright 2017 Taylor Raack.
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

import scala.sys.process.stringToProcess
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.Try

object ShellUtils {
  def runCommand(command: String, errorString: String): String = {
    runProcess(Process(command), errorString)
  }

  private def runProcess(processBuilder: scala.sys.process.ProcessBuilder, errorString: String): String = {
    val commandOutput = scala.collection.mutable.ArrayBuffer.empty[String]

    Try {
      processBuilder.lineStream(ProcessLogger(commandOutput += _)).foreach(commandOutput += _)
      commandOutput.mkString("\n")
    }.recover {
      case e =>
        throw new RuntimeException(s"$errorString; ${commandOutput.mkString("\n")}")
    }.get
  }
}
