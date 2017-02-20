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

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path
import java.nio.file.Files

import scala.sys.process.stringToProcess
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

object Encryption {
  case class Crypto(process: java.lang.Process, outReader: BufferedReader, errorReader: BufferedReader)

  val encoders = scala.collection.mutable.Map[String, Crypto]()
  val decoders = scala.collection.mutable.Map[String, Crypto]()

  def withEncryptedDir[T](unencryptedDir: String)(block: (String, String) => T): T = {
    cryptoActionWithDir(unencryptedDir, "facsimile-temp-encrypted", "encrypted", block) { encryptedDir =>
      mountEncFsForBackup(unencryptedDir, encryptedDir)
    }
  }

  def withDecryptedDir[T](encryptedDir: String, encryptionConfigFile: String)(block: (String, String) => T): T = {
    cryptoActionWithDir(encryptedDir, "facsimile-temp-decrypted", "decrypted", block) { decryptedDir =>
      mountEncFsForRestore(encryptedDir, decryptedDir, encryptionConfigFile)
    }
  }

  def encodePath(rootDir: String, unencryptedPath: String): String = {
    cryptoActionOnPath(rootDir, unencryptedPath, encoders, "encode")
  }

  def decodePath(rootDir: String, encryptedPath: String): String = {
    cryptoActionOnPath(rootDir, encryptedPath, decoders, "decode")
  }

  private def cryptoActionOnPath(rootDir: String, cryptoPath: String, cryptos: scala.collection.mutable.Map[String, Crypto], cryptoCommand: String): String = {
    synchronized {
      // get the encoder for this rootDir, creating a new one if necessary
      val crypto = cryptos.get(rootDir) match {
        case None => {
          val process = new ProcessBuilder("sudo", "encfsctl", cryptoCommand, s"--extpass='${InternalConfiguration.facsimileShareDir}/facsimile-password'", "--", rootDir).start()
          val crypto = Crypto(
            process,
            new BufferedReader(new InputStreamReader(process.getInputStream())),
            new BufferedReader(new InputStreamReader(process.getErrorStream()))
          )
          cryptos.put(rootDir, crypto)
          crypto
        }
        case Some(crypto) => crypto
      }

      // write path to external program
      crypto.process.getOutputStream.write(cryptoPath.getBytes)
      crypto.process.getOutputStream.write(System.lineSeparator.getBytes)
      crypto.process.getOutputStream.flush()
      // wait for input from program

      val output = crypto.outReader.readLine()
      if (crypto.errorReader.ready()) {
        // there is data on the error stream
        val error = crypto.errorReader.readLine()
        if (!crypto.process.isAlive()) {
          // process died; remove it from the encoder map
          cryptos.remove(rootDir)
        }
        throw new RuntimeException(s"Could not $cryptoCommand path $cryptoPath; error is $error")
      }
      output
    }
  }

  private def cryptoActionWithDir[T](cryptoBaseDir: String, dirPrefix: String, dirFunction: String, block: (String, String) => T)(function: String => Unit): T = {
    val theDir = Files.createTempDirectory(dirPrefix)
    function(theDir.toString)
    try {
      block(theDir.toString, s"$cryptoBaseDir/.encfs6.xml")
    } finally {
      Try {
        ShellUtils.runCommand(s"sudo fusermount -u $theDir", s"Could not unmount $dirFunction directory $theDir")
        ()
      }.recover {
        case NonFatal(e) =>
          println(e)
      }.get
    }
  }

  private def mountEncFsForBackup(source: String, destination: String): Unit = {
    mountEncFs(source, destination, "", "--reverse -o ro")
  }

  private def mountEncFsForRestore(source: String, destination: String, encfsConfigPath: String): Unit = {
    System.out.println(s"encfs config path is $encfsConfigPath; ")
    mountEncFs(source, destination, s"ENCFS6_CONFIG=$encfsConfigPath", "")
  }

  private def mountEncFs(source: String, destination: String, prefix: String, extraOptions: String): Unit = {
    val initialMessages = scala.collection.mutable.ArrayBuffer.empty[String]
    Try {
      // TODO - get password from user
      // NEVER STORE THE USER'S PASSWORD IN CLEARTEXT ON DISK - why?
      // ONLY USE IT TEMPORARILY ONCE WHEN ENCFS CONFIG FILE IS MISSING
      System.out.println(s"running sudo mkdir -p $destination &&  sudo $prefix encfs --standard --extpass='${InternalConfiguration.facsimileShareDir}/facsimile-password' $extraOptions $source $destination")
      (Process(s"sudo mkdir -p $destination") #&&
        Process(s"sudo $prefix encfs --standard --extpass='${InternalConfiguration.facsimileShareDir}/facsimile-password' $extraOptions $source $destination")).
        lineStream(ProcessLogger(line => { initialMessages += line; () })).
        foreach(line => { initialMessages += line })
      println(initialMessages.mkString(System.lineSeparator))
    }.recover {
      case NonFatal(ex) =>
        val errorRegex = """Nonzero exit code: (\d+)""".r
        ex.getMessage match {
          case errorRegex(code) => {
            throw new RuntimeException(s"Encfs mount error code $code: ${System.lineSeparator}${initialMessages.mkString(System.lineSeparator)}") // non fatal
          }
          case _ => {
            throw new RuntimeException(s"Encfs mount error:${System.lineSeparator}${initialMessages.mkString(System.lineSeparator)}")
          }
        }
    }.get
  }
}
