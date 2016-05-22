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

import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileSystems
import java.time.Instant
import java.time.Month
import java.time.ZoneId
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

import scala.sys.process.stringToProcess
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.google.gson.Gson

object Backup {

  val totalPath = FileSystems.getDefault().getPath("/", "var", "cache", "facsimile", "total")

  /*
   * Possible backup commands:
   * 
   * Local side must run as root, so that all files can be read for backup
   * Remote side must allow snapshots to be taken, test for this
   * 
   * rsync notes
   * whole-file is used for both local paths, because bandwidth between source and destination is greater than bandwidth to storage
   * i believe (verify through experiment) that no-whole-file be used for sshfs systems, because even though the destination looks like it is local, it is actually remote
   * 
   * local:
   * local non-snapshotting (no zfs or 32 bit) -> local: sudo rsync --link-dest some-dir / /backup/
   * local snapshotting (zfs and 64 bit) -> local: zfs send | zfs receive
   * 
   * remote:
   * remote snapshots available:
   * local non-snapshotting (or local snapshotting != remote snapshotting) -> remote root: sudo rsync / root@server:/backup/, snapshot remote system
   * local non-snapshotting (or local snapshotting != remote snapshotting) -> remote no-root: sudo rsync --fake-super user@server:/backup/; snapshot remote system
   * local snapshotting (snapshot filesystems compatible) -> remote root: local snapshot; zfs send | ssh root@server zfs receive
   * local snapshotting (snapshot filesystems compatible)> remote no-root: local snapshot; zfs send | ssh root@server zfs receive
   * 
   * remote snapshots not available:
   * local -> remote root: sudo rsync --link-dest some-path / root@server:/backup/
   * local -> remote no-root: sudo rsync --fake-super --link-dest some-path / user@server:/backup/
   * 
   * may need to lookup all permissions if remote host does not support xattrs (can find this by backing up a single file and testing for correct restoration), then will need to collect all ownership / permissions / ACLs / xattrs and store them, then put them back 
   * 
   * what if the local machine has multiple mountpoints for backups? that complicates remote backups because then we have to have multiple zfs volumes
   * but - maybe this is ok, because we can tell the user "we need create, snapshot, delete, etc permissions for your user on your destination host. run these commands: xyz"
   * 
   */

  // TODO - allow status callbacks so CLI can have information and print it there
  def process(source: Filesystem, target: Host, destination: Filesystem, tempConfig: Map[String, Object], progressNotifier: (Int) => Unit): Try[String] = {

    (source, destination) match {
      case (s: PipedTransferSupported, d: PipedTransferSupported) if s.pipedTransferType == d.pipedTransferType => {
        // source and destination support piped transfer and they use the same mechanism
        Failure(new IllegalArgumentException("Piped transfer not yet supported"))
      }
      case _ => {
        // mount zfs
        //"sudo zpool import -d /home/traack/testbackup traackbackup" !!

        // TODO - try fakesuper send and restore of single test file to ensure that xattrs can be stored
        // if not, ask if they can login as root
        // if not, warn user that backup and restore will take longer than necessary until they can enable xattrs for
        // the destination filesystem OR they can login as root

        //val one = s"mkdir -p $mountDir" !!
        //val two = s"sudo sshfs root@backup:/mnt/tank/backup/lune-rsnapshot/backup/localhost/ $mountDir" !!

        // backup

        val defaultExcludes = Seq(".gvfs", ".cache/*", ".thumbnails*", "[Tt]rash*",
          "*.backup*", "*~", ".dropbox*", "/proc", "/sys",
          "/dev", "/run", "/etc/mtab", "/media", "/net",
          "/var/cache/apt/archives/*.deb", "lost+found/*",
          "/tmp", "/var/tmp", "/var/backups", ".Private")

        val customExcludes = Seq("/backup", "/backupmount", "/sshfs", "/var/lib/mlocate/*", ".recoll/xapiandb", ".gconf.old/system/networking/connections", ".local/share/zeitgeist.old")

        val allExcludes = (defaultExcludes ++ customExcludes)

        val path = Files.createTempFile("facsimile", "config")

        Files.write(path, allExcludes.mkString("\n").getBytes)

        var totalFiles: Long = Try {
          // try to read file total from previous file total
          new String(Files.readAllBytes(totalPath)).toLong
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
        var latestPercent: Int = 0

        val incrementalPattern = """.*(ir-chk)=.*""".r
        val totalPattern = """.*to-chk=(\d+)\/(\d+).*""".r
        val uptodate = """.*(uptodate).*""".r
        val hidingfile = """.*(hiding file).*""".r
        val rsyncMessage = """rsync:\s(.*)""".r

        println(s"total files to transfer: $totalFiles")
        progressNotifier(0)
        // -M--fake-super to write user / group information into xattrs
        // --inplace to not re-write destination file (preserves bits for destination COW)

        // TODO - first time connecting to host, run
        // ssh-keyscan -H 192.168.147.30 >> /var/lib/facsimile/.ssh/known_hosts
        // ssh-keyscan -H transmission >> /var/lib/facsimile/.ssh/known_hosts
        // and then unique the ~/.ssh/known_hosts file
        val remoteHostDestination = s"${tempConfig("remote-host-user")}@${tempConfig("remote-host")}:${tempConfig("remote-host-path")}"
        val command = s"""sudo nice -n 19 rsync -aHAXvv -M--fake-super --inplace --progress --omit-link-times --delete --exclude-from=${path.toFile.toString} --numeric-ids --delete-excluded / $remoteHostDestination/"""

        println(command)

        // TODO - do something else with this output
        val fw = new FileWriter("/tmp/backup_output")

        def computePercent(newCompleted: Long, newTotalFiles: Long): Unit = {
          completed = newCompleted
          totalFiles = newTotalFiles

          // TODO - percentage complete may not actually be accurate - need to verify that #completed accounting is actually correct

          val percent = if (totalFiles > 0) (100 * completed / totalFiles).toInt else 0

          if (percent != latestPercent) {
            latestPercent = percent
            progressNotifier(latestPercent)
          }
        }

        val rsyncMessages = scala.collection.mutable.ArrayBuffer.empty[String]

        val backupMessage = Try {
          // TODO - the rsync process can hang. this should be executed in some other thread and we should watch for log lines.
          // if there hasn't been some log line in, 5 minutes, kill the rsync process and start again.
          Process(command).lineStream(ProcessLogger(line => rsyncMessages += line)).foreach(line => {
            line match {
              case incremental @ (uptodate(_) | hidingfile(_) | incrementalPattern(_)) => { computePercent(completed + 1, totalFiles) }
              case totalPattern(toGo, newTotal) => { computePercent(newTotal.toLong - toGo.toLong, newTotal.toLong) }
              case rsyncMessage(message) => { rsyncMessages += message }
              case others => { fw.write(others); fw.write("\n") }
            }
          })
        } match {
          case Failure(e) => {
            val errorRegex = """Nonzero exit code: (\d+)""".r
            e.getMessage match {
              case errorRegex(code) => {
                code.toInt match {
                  // could not delete all files because of max delete OR
                  // some files not transfered because they disappeared first - not a problem
                  case 24 | 25 => Success("")
                  case 23 => Success(s"Some files not transfered due to an error\n${rsyncMessages.mkString("\n")}")
                  case other => Failure(new RuntimeException(s"Backup error\n${rsyncMessages.mkString("\n")}")) // non fatal
                }
              }
              case other => Failure(new RuntimeException(s"Backup error:\n${rsyncMessages.mkString("\n")}"))
            }
          }
          case other => Success("")
        }

        backupMessage match {
          case Success(message) => {
            fw.close()

            Files.write(totalPath, completed.toString.getBytes)

            println(s"total transferred: $completed; total rsync said would be transferred: $totalFiles")
            // snapshot
            val snapshotInstant = Instant.now()
            val command2 = s"ssh ${tempConfig("remote-host-user")}@${tempConfig("remote-host")} zfs snapshot ${tempConfig("dataset")}@facsimile-${snapshotInstant.toString}"
            println(command2)
            val output2 = command2 !!

            cullSnapshots(snapshotInstant, tempConfig)
          }
          case e => e
        }

        // unmount
        //"sudo fusermount -u /tmp/newbackup" !!
      }
    }
  }

  private def formatHour(time: Instant): String = {
    val hourFormatter = DateTimeFormatter.ofPattern("h:mm a")
      .withLocale(Locale.ENGLISH)
      .withZone(ZoneId.systemDefault())

    val ty = if (LocalDateTime.ofInstant(time, ZoneId.systemDefault()).isAfter(LocalDateTime.of(LocalDate.now(), LocalTime.MIDNIGHT))) "Today, " else "Yesterday, "
    ty + hourFormatter.format(time)
  }

  def snapshots(tempConfig: Map[String, Object]): Seq[String] = {
    val current = Instant.now()
    val oneDayBack = current.minus(1, ChronoUnit.DAYS)
    val oneMonthBack = current.minus(30, ChronoUnit.DAYS)

    val monthFormatter = DateTimeFormatter.ofPattern("MMMM YYYY")
      .withLocale(Locale.ENGLISH)
      .withZone(ZoneId.systemDefault())

    val dayFormatter = DateTimeFormatter.ofPattern("MMMM d, YYYY")
      .withLocale(Locale.ENGLISH)
      .withZone(ZoneId.systemDefault())

    snapshotTimes(tempConfig)
      .sortWith(_.toString < _.toString)
      .map(x => {
        if (x.isBefore(oneMonthBack)) { monthFormatter.format(x) } else if (x.isBefore(oneDayBack)) { dayFormatter.format(x) } else { formatHour(x) }
      })
  }

  private def snapshotTimes(tempConfig: Map[String, Object]): Seq[Instant] = {
    val dataset = tempConfig("dataset")
    val length = (dataset + "@facsimile-").length
    val output: String = s"ssh ${tempConfig("remote-host-user")}@${tempConfig("remote-host")} zfs list -t snapshot -r $dataset" !!

    var current = Instant.now()
    val oneDayBack = current.minus(1, ChronoUnit.DAYS)
    val oneMonthBack = current.minus(30, ChronoUnit.DAYS)

    output.split("\n")
      .flatMap { str => Try { Instant.parse(str.substring(length, length + 24)) }.toOption }
  }

  private def cullSnapshots(currentSnapshot: Instant, tempConfig: Map[String, Object]): Try[String] = {
    Try {
      // keep all hourly snapshots for last 24 hours
      // keep all daily backups for the last month
      // keep all weekly backups forever

      // go through snapshots in reverse order.
      // loop:
      //   if visited snapshot > waiting for date, delete
      //   else { 
      //     if now - visited snapshot < 24 hours while (waiting for > visited snapshot) waiting for = waiting for - 1 hour
      //     else if now - visited snapshot < 30 days while (waiting for > visited snapshot) waiting for = waiting for - 1 day
      //     else while (waiting for > visited snapshot) waiting for = waiting for - 1 week

      val oneDayBack = LocalDateTime.ofInstant(currentSnapshot, ZoneId.systemDefault()).minus(1, ChronoUnit.DAYS)
      val oneMonthBack = oneDayBack.minus(30, ChronoUnit.DAYS)

      val weekFields = WeekFields.of(Locale.getDefault())
      val weekField = weekFields.weekOfWeekBasedYear()

      val set = scala.collection.mutable.Set.empty[String]
      snapshotTimes(tempConfig)
        .sortWith(_.toString > _.toString)
        .foreach(snapshotDate => {
          val bucket = LocalDateTime.ofInstant(snapshotDate, ZoneId.systemDefault()) match {
            case x if x.isAfter(oneDayBack) => "hour" + x.getHour
            case x if x.isBefore(oneDayBack) && x.isAfter(oneMonthBack) => "day" + x.getDayOfMonth
            case x => "year" + x.getYear + "week" + x.get(weekField)
          }

          if (set.contains(bucket)) {
            val command = s"ssh ${tempConfig("remote-host-user")}@${tempConfig("remote-host")} zfs destroy ${tempConfig("dataset")}@facsimile-$snapshotDate"
            println(s"deleting snapshot $snapshotDate with $command")
            command !!
          } else {
            println(s"found new element for bucket $bucket")
            set += bucket
          }
        })
      ""
    }
  }
}
