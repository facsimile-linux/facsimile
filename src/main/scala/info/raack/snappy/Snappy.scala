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

import scala.util.Try

class Snappy(configFile: String = "/etc/snappy.conf") {

  val scheduleFile = FileSystems.getDefault().getPath("/", "var", "lib", "snappy", "scheduled")

  // TODO - use user snappy, which must have root privs to get all data
  // val schedule = s"0 * * * * traack /usr/bin/snappy backup"

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
    if (Files.exists(scheduleFile)) {
      backup()
    }
  }

  def backup(): Unit = {
    // check for presence of cron task
    Option(new FileOutputStream("/var/lock/snappy").getChannel().tryLock()).map { x =>
      Backup.process(sourceFilesystem, targetHost, targetFilesystem)
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
}
