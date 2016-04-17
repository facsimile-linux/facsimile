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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileSystems
import java.time.Instant

import scala.sys.process.stringToProcess
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.Try

object Backup {
  def process(source: Filesystem, target: Host, destination: Filesystem): Unit = {

    (source, destination) match {
      case (s: PipedTransferSupported, d: PipedTransferSupported) if s.pipedTransferType == d.pipedTransferType => {
        // source and destination support piped transfer and they use the same mechanism

      }
      case _ => {
        try {
          // mount zfs
          //"sudo zpool import -d /home/traack/testbackup traackbackup" !!

          var mountDir = "/tmp/newbackup3"
          //val one = s"mkdir -p $mountDir" !!
          //val two = s"sudo sshfs root@backup:/mnt/tank/backup/lune-rsnapshot/backup/localhost/ $mountDir" !!

          // backup

          val defaultExcludes = Seq(".gvfs", ".cache/*", ".thumbnails*", "[Tt]rash*",
            "*.backup*", "*~", ".dropbox*", "/proc/*", "/sys/*",
            "/dev/*", "/run/*", "/etc/mtab",
            "/var/cache/apt/archives/*.deb", "lost+found/*",
            "/tmp/*", "/var/tmp/*", "/var/backups/*", ".Private")

          val customExcludes = Seq("/backup", "/backupmount", "/net", "/sshfs", "/media", "/var/lib/mlocate/*", ".recoll/xapiandb", ".gconf.old/system/networking/connections", ".local/share/zeitgeist.old", "/nfs", mountDir)

          val path = Files.createTempFile("snappy", "config")

          Files.write(path, (defaultExcludes ++ customExcludes).mkString("\n").getBytes)

          val command = s"sudo /usr/bin/rsync -aHAv --progress --omit-link-times --delete --exclude-from=${path.toFile.toString} --numeric-ids --delete-excluded / $mountDir/"

          println(command)

          def logstdout(line: String): Unit = { println("stdout " + line) }
          def logstderr(line: String): Unit = { println("stderr " + line) }

          val incrementalPattern = """.*ir-chk=(\d+)\/(\d+).*""".r
          val totalPattern = """.*to-chk=(\d+)\/(\d+).*""".r

          var current: Long = 0
          // TODO - in else clause, use number of inodes on system to estimate total number of files
          var total: Long = Try { Files.readAllBytes(FileSystems.getDefault().getPath("/", "var", "cache", "snappy", "total")).toString.toLong }.getOrElse(0)

          var latestPercent = ""
          def printCompletion(newTotal: Long): Unit = {
            if (total != newTotal) {
              Files.write(FileSystems.getDefault().getPath("/", "var", "cache", "snappy", "total"), newTotal.toString.getBytes)
              total = newTotal
            }
            val percent = if (total == 0) {
              "calculating..."
            } else {
              val percent = current / total
              s"${percent}%"
            }
            val newPercent = s"Percent complete: ${percent}"
            if (newPercent != latestPercent) {
              latestPercent = newPercent
              println(latestPercent)
            }
          }

          val output = Process(command).lineStream(ProcessLogger(line => ())).foreach(line => {
            line match {
              case incrementalPattern(part, total) => { current = part.toLong; printCompletion(0) }
              case totalPattern(part, newTotal) => { current = part.toLong; printCompletion(newTotal.toLong) }
              case _ => ()
            }
          })

          // snapshot
          val time = Instant.now().toString

          val command2 = s"ssh root@backup zfs snapshot tank/backup/lune-rsnapshot@$time"
          println(command2)
          val output2 = command2 !!

          val command3 = "ssh root@backup zfs list -t snapshot"

          println(command3)
          command3 !!

        } catch {
          case e: Exception => {
            e.printStackTrace()
          }
        } finally {
          // unmount
          //"sudo fusermount -u /tmp/newbackup" !!
        }
      }
    }
  }
}
