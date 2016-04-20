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

import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileSystems
import java.time.Instant

import scala.sys.process.stringToProcess
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.Try

object Backup {
  // TODO - allow status callbacks so CLI can have information and print it there
  def process(source: Filesystem, target: Host, destination: Filesystem): Unit = {

    (source, destination) match {
      case (s: PipedTransferSupported, d: PipedTransferSupported) if s.pipedTransferType == d.pipedTransferType => {
        // source and destination support piped transfer and they use the same mechanism

      }
      case _ => {
        try {
          // mount zfs
          //"sudo zpool import -d /home/traack/testbackup traackbackup" !!

          // TODO - try fakesuper send and restore of single test file to ensure that xattrs can be stored
          // if not, ask if they can login as root
          // if not, warn user that backup and restore will take longer than necessary until they can enable xattrs for
          // the destination filesystem OR they can login as root

          var mountDir = "traack@transmission:/mnt/tank/backup/lune-rsnapshot/backup/localhost"
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

          var total: Long = Try {
            // try to read file total from previous file total
            new String(Files.readAllBytes(FileSystems.getDefault().getPath("/", "var", "cache", "snappy", "total"))).toLong
          }.getOrElse({
            // find number of inodes on system to estimate total number of files
            val pattern = """(\S+)\s+(\S+)\s+(\d+)""".r
            Process("/bin/df --output=target,fstype,iused").lineStream
              .flatMap({ case pattern(target, fstype, iused) => Some((target, fstype, iused.toLong)); case _ => None })
              .filterNot(item => allExcludes.contains(item._1))
              .filterNot(item => Seq("ecryptfs").contains(item._2))
              .map(_._3)
              .sum
          })

          var completed: Long = 0
          var latestPercent: Long = 0
          var latestTime = System.currentTimeMillis()
          var startTime = System.currentTimeMillis()

          val lastTotalMillis = Try {
            Some(new String(Files.readAllBytes(FileSystems.getDefault().getPath("/", "var", "cache", "snappy", "totaltime"))).toLong)
          }.getOrElse(None)

          println(s"last total millis: $lastTotalMillis")

          def printCompletion(newTotal: Long): Unit = {
            if (total != newTotal) {
              println(s"new total: $newTotal")
              total = newTotal
            }

            // TODO - percentage complete may not actually be accurate - need to verify that #completed accounting is actually correct

            val percent = 100 * completed / total
            if (percent != latestPercent) {
              // do some kind of estimated time smoothing based on amount of time to complete percentage so far

              // smooth with time to complete previous backup as a factor
              // adjust smoothing weights based on percent completed
              // previous backup weight = 100 - percent
              // current backup weight = percent
              // current percent weight = 25 ( might be 0, as how much can only the last percent inform the entire remainder of the backup?)
              val newLatestTime = System.currentTimeMillis()
              val percentToComplete = 100 - percent
              val incrementalMillisPerPercent = (newLatestTime - latestTime) / (percent - latestPercent)
              val incrementalMillisToComplete = percentToComplete * incrementalMillisPerPercent
              val totalMillisPerPercent = (newLatestTime - startTime) / percent

              val totalMillisToComplete = (percentToComplete * totalMillisPerPercent, percent)
              val previousMillisToComplete: (Long, Long) = lastTotalMillis.map(x => (percentToComplete * x / 100, 100 - percent)).getOrElse((0, 0))

              println(s"previous millis to complete: $previousMillisToComplete; current millis to complete: $totalMillisToComplete; incremental millis to complete: $incrementalMillisToComplete")
              val minutesToComplete = (totalMillisToComplete._1 * totalMillisToComplete._2 +
                previousMillisToComplete._1 * previousMillisToComplete._2) / 60000 / (totalMillisToComplete._2 + previousMillisToComplete._2)

              latestPercent = percent
              latestTime = newLatestTime
              println(s"Percent complete: ${percent}% ($minutesToComplete minutes estimated remaining)")
            }
          }

          val incrementalPattern = """.*(ir-chk)=.*""".r
          val totalPattern = """.*to-chk=(\d+)\/(\d+).*""".r
          val uptodate = """.*(uptodate).*""".r
          val hidingfile = """.*(hiding file).*""".r
          val rsyncMessage = """rsync:\s(.*)""".r

          println(s"total files to transfer: $total")
          printCompletion(total)
          // -M--fake-super to write user / group information into xattrs
          // --inplace to not re-write destination file (preserves bits for destination COW)

          val command = s"sudo /usr/bin/rsync -aHAvv -M--fake-super --inplace --progress --omit-link-times --delete --exclude-from=${path.toFile.toString} --numeric-ids --delete-excluded / $mountDir/"

          println(command)

          val fw = new FileWriter("/tmp/backup_output")

          val rsyncMessages = scala.collection.mutable.ArrayBuffer.empty[String]
          try {
            Process(command).lineStream(ProcessLogger(line => ())).foreach(line => {
              line match {
                case incremental @ (uptodate(_) | hidingfile(_) | incrementalPattern(_)) => { completed += 1; printCompletion(total) }
                case totalPattern(toGo, newTotal) => { completed = newTotal.toLong - toGo.toLong; printCompletion(newTotal.toLong) }
                case rsyncMessage(message) => { rsyncMessages += message }
                case others => { fw.write(others); fw.write("\n") }
              }
            })
          } catch {
            case e: Exception => {
              println(s"Messages from rsync: \n${rsyncMessages.mkString("\n")}")
            }
          }

          fw.close()

          val endTime = System.currentTimeMillis()

          Files.write(FileSystems.getDefault().getPath("/", "var", "cache", "snappy", "total"), completed.toString.getBytes)
          Files.write(FileSystems.getDefault().getPath("/", "var", "cache", "snappy", "totaltime"), (endTime - startTime).toString.getBytes)

          println(s"total transferred: $completed; total rsync said would be transferred: $total")
          // snapshot
          val time = Instant.now().toString

          val command2 = s"ssh root@backup zfs snapshot tank/backup/lune-rsnapshot@$time"
          println(command2)
          val output2 = command2 !!

          val command3 = "ssh storage zfs list -t snapshot"

          // TODO - remove old snapshots

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

  def snapshots(): Seq[Snapshot] = {
    Seq()
  }
}
