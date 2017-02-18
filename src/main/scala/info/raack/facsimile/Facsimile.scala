/**
 * This file is part of Facsimile.
 *
 * (C) Copyright 2016,2017 Taylor Raack.
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

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.FileSystems
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.HashSet

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.JavaConverters.mapAsScalaMapConverter

import org.json4s.{DefaultFormats, NoTypeHints}
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.write

class Facsimile {

  val configDir = Option(System.getProperty("testingConfigDir")).map(Paths.get(_))
    .getOrElse(FileSystems.getDefault().getPath("/", "var", "lib", "facsimile"))

  val cacheDir = Option(System.getProperty("testingCacheDir")).map(Paths.get(_))
    .getOrElse(FileSystems.getDefault().getPath("/", "var", "cache", "facsimile"))

  val statusPath = Paths.get(cacheDir.toString(), "status")
  val lockFilePath = FileSystems.getDefault().getPath("/", "var", "lock", "facsimile")
  val configPath = configDir.resolve("config")
  val lastStartTimePath = Paths.get(cacheDir.toString(), "lastStartTime")
  val totalTimePath = Paths.get(cacheDir.toString(), "totaltime")
  var lastPercentChange = System.currentTimeMillis()
  val startTime = System.currentTimeMillis()

  val configurationResolver = new ConfigurationResolver()

  var config: Configuration = configurationResolver.resolve(Try {
    new String(Files.readAllBytes(configPath))
  }.getOrElse(""))

  val lastStartMillis = Try {
    Some(new String(Files.readAllBytes(lastStartTimePath)).toLong)
  }.getOrElse(None)

  val lastTotalMillis = Try {
    Some(new String(Files.readAllBytes(totalTimePath)).trim.toLong)
  }.getOrElse(None)

  // TODO - rotate with /etc/logrotate.d/package
  /*
   * /var/log/facsimile.log {
         weekly
         missingok
         rotate 12
         compress
         copytruncate
         notifempty
         create 640 facsimile adm
     }
   */

  private def getStatusString(minutesToCompleteOption: Option[Long]): String = {
    implicit val formats = Serialization.formats(NoTypeHints)
    write(Map("time_remaining" -> minutesToCompleteOption.getOrElse("unknown")))
  }

  def scheduledBackup(): Unit = {
    println(s"Starting up at ${Instant.now()}")
    if (shouldBackup()) {
      backup()
    }
  }

  def backup(): Unit = {
    Files.write(statusPath, getStatusString(None).getBytes)
    setReadAllPerms(statusPath)
    // check for presence of cron task
    createLockFile()
    Option(FileChannel.open(lockFilePath, StandardOpenOption.WRITE).tryLock()) match {
      case Some(lock) => {
        try {
          Backup.process(config, printCompletion)
          val endTime = System.currentTimeMillis()
          Files.write(lastStartTimePath, startTime.toString.getBytes)
          setReadAllPerms(lastStartTimePath)
          Files.write(totalTimePath, (endTime - startTime).toString.getBytes)
        } finally {
          lock.release()
        }
      }
      case None => new RuntimeException("Could not get lock")
    }
  }

  private def createLockFile(): Unit = {
    Try {
      val perms = new HashSet[PosixFilePermission]()
      perms.add(PosixFilePermission.OWNER_READ)
      perms.add(PosixFilePermission.OWNER_WRITE)
      perms.add(PosixFilePermission.GROUP_READ)
      perms.add(PosixFilePermission.GROUP_WRITE)
      perms.add(PosixFilePermission.OTHERS_READ)
      perms.add(PosixFilePermission.OTHERS_WRITE)
      Files.createFile(lockFilePath, PosixFilePermissions.asFileAttribute(perms))
    }
  }

  def schedule(turnOn: Boolean): Unit = {
    config = config match { case x: RemoteConfiguration => x.copy(automaticBackups = turnOn) }
    writeConfig()
  }

  def snapshots(): Map[String, String] = {
    Backup.snapshots(config)
  }

  def getConfiguration(): String = {
    configurationResolver.serialize(config)
  }

  def setConfiguration(newConfig: String): Unit = {
    config = configurationResolver.resolve(newConfig)
    writeConfig()
  }

  def testConfiguration(config: String): ConfigurationTestResult = {
    ConfigurationAssistant.testConfiguration(configurationResolver.resolve(config))
  }

  def getSnapshotFiles(snapshot: String, directory: String): Seq[SnapshotFile] = {
    Backup.getSnapshotFiles(snapshot, directory, config)
  }

  def restoreSnapshotFiles(snapshot: String, backupPath: String, restorePath: String): Unit = {
    Backup.restoreSnapshotFiles(config, snapshot, backupPath, restorePath)
  }

  private def writeConfig(): Unit = {
    Files.write(configPath, configurationResolver.serialize(config).getBytes)
    setReadAllPerms(configPath)
  }

  private def setReadAllPerms(path: Path): Unit = {
    val perms = new HashSet[PosixFilePermission]()
    perms.add(PosixFilePermission.OWNER_READ)
    perms.add(PosixFilePermission.OWNER_WRITE)
    perms.add(PosixFilePermission.GROUP_READ)
    perms.add(PosixFilePermission.OTHERS_READ)
    Files.setPosixFilePermissions(path, perms)
  }

  private def shouldBackup(): Boolean = {
    // don't back up if last backup duration / (now - last backup time start) > 10%
    val scheduled = config.automaticBackups
    val overdue = overdueForBackup()
    println(s"scheduled: $scheduled; overdue: $overdue")
    scheduled && overdue
  }

  private def overdueForBackup(): Boolean = {
    // we are overdue for backup if the last backup duration fraction of the time elapsed since last start is less than 25%
    lastTotalMillis.getOrElse(0.toLong).toFloat / (System.currentTimeMillis() - lastStartMillis.getOrElse(0.toLong)).toFloat < 0.25
  }

  private def printCompletion(percentCompleted: Int): Unit = {

    // do some kind of estimated time smoothing based on amount of time to complete percentage so far

    // smooth with time to complete previous backup as a factor
    // adjust smoothing weights based on percent completed
    // previous backup weight = 100 - percent
    // current backup weight = percent
    // current percent weight = 25 ( might be 0, as how much can only the last percent inform the entire remainder of the backup?)
    val newLatestTime = System.currentTimeMillis()
    val percentToComplete = 100 - percentCompleted
    val totalMillisPerPercent = if (percentCompleted > 0) (newLatestTime - startTime) / percentCompleted else 0

    val totalMillisToComplete = (percentToComplete * totalMillisPerPercent, percentCompleted)
    val previousMillisToComplete = lastTotalMillis.map(x => (percentToComplete * x / 100, 100 - percentCompleted)).getOrElse((0.toLong, 1))

    println(s"previous millis to complete: $previousMillisToComplete; current millis to complete: $totalMillisToComplete")
    val minutesToComplete = (totalMillisToComplete._1 * totalMillisToComplete._2 +
      previousMillisToComplete._1 * previousMillisToComplete._2) / 60000 / (totalMillisToComplete._2 + previousMillisToComplete._2)

    Files.write(statusPath, getStatusString(Some(minutesToComplete)).getBytes)
    lastPercentChange = newLatestTime
    println(s"Percent complete: ${percentCompleted}% ($minutesToComplete minutes estimated remaining)")
  }
}
