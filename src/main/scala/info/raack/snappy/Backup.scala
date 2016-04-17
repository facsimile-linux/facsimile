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
            "*.backup*", "*~", ".dropbox*", "/proc", "/sys",
            "/dev", "/run", "/etc/mtab", "/media", "/net",
            "/var/cache/apt/archives/*.deb", "lost+found/*",
            "/tmp", "/var/tmp", "/var/backups", ".Private", mountDir)

          val customExcludes = Seq("/backup", "/backupmount", "/sshfs", "/var/lib/mlocate/*", ".recoll/xapiandb", ".gconf.old/system/networking/connections", ".local/share/zeitgeist.old")

          val allExcludes = (defaultExcludes ++ customExcludes)

          val path = Files.createTempFile("snappy", "config")

          Files.write(path, allExcludes.mkString("\n").getBytes)

          // TODO - in else clause, use number of inodes on system to estimate total number of files
          var total: Long = Try {
            // try to read file total from previous file total
            new String(Files.readAllBytes(FileSystems.getDefault().getPath("/", "var", "cache", "snappy", "total"))).toLong
          }.getOrElse({
            // find number of inodes
            val pattern = """(\S+)\s+(\S+)\s+(\d+)""".r
            Process("/bin/df --output=target,fstype,iused").lineStream
              .flatMap(_ match { case pattern(target, fstype, iused) => Some((target, fstype, iused.toLong)); case _ => None })
              .filterNot(item => allExcludes.contains(item._1))
              .filterNot(item => Seq("ecryptfs").contains(item._2))
              .map(_._3)
              .sum
          })

          var completed: Long = 0
          var latestPercent: Long = 0
          var latestTime = System.currentTimeMillis()

          def printCompletion(newTotal: Long): Unit = {
            if (total != newTotal) {
              println(s"new total: $newTotal")
              Files.write(FileSystems.getDefault().getPath("/", "var", "cache", "snappy", "total"), newTotal.toString.getBytes)
              total = newTotal
            }

            val percent = 100 * completed / total
            if (percent != latestPercent) {
              val newLatestTime = System.currentTimeMillis()
              val millisPerPercent = (newLatestTime - latestTime) / (percent - latestPercent)
              val percentToComplete = 100 - percent
              val millisToComplete = percentToComplete * millisPerPercent
              val minutesToComplete = millisToComplete / 60000

              latestPercent = percent
              latestTime = newLatestTime
              println(s"Percent complete: ${percent}% ($minutesToComplete minutes estimated remaining)")
            }
          }

          val incrementalPattern = """.*(ir-chk)=.*""".r
          val totalPattern = """.*to-chk=\d+\/(\d+).*""".r
          val uptodate = """.*(uptodate).*""".r
          val hidingfile = """.*(hiding file).*""".r

          println(s"total files to transfer: $total")
          printCompletion(total)

          val command = s"sudo /usr/bin/rsync -aHAvv --progress --omit-link-times --delete --exclude-from=${path.toFile.toString} --numeric-ids --delete-excluded / $mountDir/"

          println(command)
          val output = Process(command).lineStream(ProcessLogger(line => ())).foreach(line => {
            line match {
              case incremental @ (uptodate(_) | hidingfile(_) | incrementalPattern(_)) => { completed += 1; printCompletion(total) }
              case totalPattern(newTotal) => { completed += 1; printCompletion(newTotal.toLong) }
              case others => {}
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
