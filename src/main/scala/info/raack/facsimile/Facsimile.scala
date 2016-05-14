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

import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileSystems
import java.time.Instant

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.collection.JavaConverters.mapAsJavaMapConverter

import com.google.gson.Gson

class Facsimile(configFile: String = "/etc/facsimile.conf") {

  val statusPath = FileSystems.getDefault().getPath("/", "var", "cache", "facsimile", "status")
  val gson = new Gson()
  Files.write(statusPath, getStatusString(None).getBytes)
  val scheduleFile = FileSystems.getDefault().getPath("/", "var", "lib", "facsimile", "scheduled")
  val lastStartTimePath = FileSystems.getDefault().getPath("/", "var", "cache", "facsimile", "lastStartTime")
  val totalTimePath = FileSystems.getDefault().getPath("/", "var", "cache", "facsimile", "totaltime")
  var lastPercentChange = System.currentTimeMillis()
  val startTime = System.currentTimeMillis()

  val lastStartMillis = Try {
    Some(new String(Files.readAllBytes(lastStartTimePath)).toLong)
  }.getOrElse(None)

  val lastTotalMillis = Try {
    Some(new String(Files.readAllBytes(totalTimePath)).trim.toLong)
  }.getOrElse(None)

  // TODO - use user facsimile for cron scheduling, which must have root privs to get all data
  // "3 * * * * facsimile /usr/bin/facsimile scheduled-backup >> /var/log/facsimile.log 2>&1"

  // TODO - install /var/cache/facsimile and /var/lib/facsimile directories, owned by facsimile user

  // TODO - on install, touch file /var/log/facsimile.log and make owned by facsimile
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

  val (sourceFilesystem, targetHost, targetFilesystem) = {
    (StandardFilesystem(), Host("localhost"), ZFSFilesystem("traackbackup", Some("/home/traack/testbackup"), false))
  }

  private def getStatusString(minutesToCompleteOption: Option[Long]): String = {
    gson.toJson(Map("time_remaining" -> minutesToCompleteOption.getOrElse("unknown")).asJava)
  }

  def scheduledBackup(): Try[String] = {
    println(s"Starting up at ${Instant.now()}")
    if (shouldBackup()) {
      backup()
    } else {
      Success("Backup not required at this time.")
    }
  }

  def backup(): Try[String] = {
    // check for presence of cron task
    Option(new FileOutputStream("/var/lock/facsimile").getChannel().tryLock()).map { lock =>
      try {
        Backup.process(sourceFilesystem, targetHost, targetFilesystem, printCompletion) match {
          case Success(message) => {
            val endTime = System.currentTimeMillis()
            Files.write(lastStartTimePath, startTime.toString.getBytes)
            Files.write(totalTimePath, (endTime - startTime).toString.getBytes)
            Success(message)
          }
          case other => other
        }
      } finally {
        lock.release()
      }
    }.getOrElse(Failure(new RuntimeException("Could not get lock")))
  }

  def schedule(turnOn: Boolean): Unit = {
    if (turnOn) {
      Files.write(scheduleFile, "on".getBytes)
    } else {
      Files.delete(scheduleFile)
    }
  }

  def snapshots(): Seq[String] = {
    Backup.snapshots()
  }

  private def shouldBackup(): Boolean = {
    // don't back up if last backup duration / (now - last backup time start) > 10%
    val scheduled = Files.exists(scheduleFile)
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
