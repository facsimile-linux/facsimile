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

import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileSystems
import java.time.Instant

import scala.util.Try

class Snappy(configFile: String = "/etc/snappy.conf") {

  println(s"Starting up at ${Instant.now()}")

  val scheduleFile = FileSystems.getDefault().getPath("/", "var", "lib", "snappy", "scheduled")
  val lastStartTimePath = FileSystems.getDefault().getPath("/", "var", "cache", "snappy", "lastStartTime")
  var lastPercentChange = System.currentTimeMillis()
  val startTime = System.currentTimeMillis()

  val lastStartMillis = Try {
    new String(Files.readAllBytes(lastStartTimePath)).toLong
  }.getOrElse(0.longValue())

  val lastTotalMillis = Try {
    Some(new String(Files.readAllBytes(FileSystems.getDefault().getPath("/", "var", "cache", "snappy", "totaltime"))).toLong)
  }.getOrElse(None)

  // TODO - use user snappy for cron scheduling, which must have root privs to get all data
  // "3-59/15 * * * * snappy /usr/bin/snappy backup"

  // TODO - install /var/cache/snappy and /var/lib/snappy directories, owned by snappy user

  // TODO - on install, touch file /var/log/snappy.log and make owned by snappy
  // TODO - rotate with /etc/logrotate.d/package
  /*
   * /var/log/snappy.log {
         weekly
         missingok
         rotate 12
         compress
         copytruncate
         notifempty
         create 640 snappy adm
     }
   */

  val (sourceFilesystem, targetHost, targetFilesystem) = {
    (StandardFilesystem(), Host("localhost"), ZFSFilesystem("traackbackup", Some("/home/traack/testbackup"), false))
  }

  def scheduledBackup(): Unit = {
    // TODO - don't back up if last backup duration / (now - last backup time start) > 10%
    if (shouldBackup()) {
      backup()
    }
  }

  def backup(): Unit = {
    // check for presence of cron task
    Option(new FileOutputStream("/var/lock/snappy").getChannel().tryLock()).map { x =>
      Backup.process(sourceFilesystem, targetHost, targetFilesystem, printCompletion)

      val endTime = System.currentTimeMillis()
      Files.write(lastStartTimePath, startTime.toString.getBytes)
      Files.write(FileSystems.getDefault().getPath("/", "var", "cache", "snappy", "totaltime"), (endTime - startTime).toString.getBytes)
    }.getOrElse(println("Could not get lock"))
  }

  def schedule(turnOn: Boolean): Unit = {
    if (turnOn) {
      Files.write(scheduleFile, "on".getBytes)
    } else {
      Files.delete(scheduleFile)
    }
  }

  def snapshots(): Seq[Snapshot] = {
    Backup.snapshots()
  }

  private def shouldBackup(): Boolean = {
    val scheduled = Files.exists(scheduleFile)
    val overdue = overdueForBackup()
    println(s"scheduled: $scheduled; overdue: $overdue")
    scheduled && overdue
  }

  private def overdueForBackup(): Boolean = {
    // we are overdue for backup if the last backup duration fraction of the time elapsed since last start is less than 10%
    lastTotalMillis.getOrElse(0.toLong).toFloat / (System.currentTimeMillis() - lastStartMillis).toFloat < 0.1
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
    val previousMillisToComplete: (Long, Int) = lastTotalMillis.map(x => (percentToComplete * x / 100, 100 - percentCompleted)).getOrElse((0, 0))

    println(s"previous millis to complete: $previousMillisToComplete; current millis to complete: $totalMillisToComplete")
    val minutesToComplete = (totalMillisToComplete._1 * totalMillisToComplete._2 +
      previousMillisToComplete._1 * previousMillisToComplete._2) / 60000 / (totalMillisToComplete._2 + previousMillisToComplete._2)

    lastPercentChange = newLatestTime
    println(s"Percent complete: ${percentCompleted}% ($minutesToComplete minutes estimated remaining)")
  }
}
