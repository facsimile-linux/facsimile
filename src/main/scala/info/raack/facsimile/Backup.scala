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
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.{Path, Paths}
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
import scala.util.control.NonFatal

object Backup {

  val sourceBackupDir = Option(System.getProperty("testingSourceDir")).getOrElse("/")

  val configDir = Option(System.getProperty("testingConfigDir")).map(Paths.get(_))
    .getOrElse(FileSystems.getDefault().getPath("/", "var", "lib", "facsimile"))

  val cacheDir = Option(System.getProperty("testingCacheDir")).map(Paths.get(_))
    .getOrElse(FileSystems.getDefault().getPath("/", "var", "cache", "facsimile"))

  val zfsPrefix = Option(System.getProperty("testingSnapshotPrefix")).getOrElse("")

  val totalPath = Paths.get(cacheDir.toString, "total")
  val tempBackupLogPath = Files.createTempFile("facsimile-log", null)
  tempBackupLogPath.toFile().deleteOnExit()

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
  def process(config: Configuration, progressNotifier: (Int) => Unit): Unit = {

    (None, None) match {
      case (s: PipedTransferSupported, d: PipedTransferSupported) if s.pipedTransferType == d.pipedTransferType => {
        // source and destination support piped transfer and they use the same mechanism
        throw new IllegalArgumentException("Piped transfer not yet supported")
      }
      case _ => {
        // mount zfs
        //"sudo zpool import -d /home/traack/testbackup traackbackup" !!

        // TODO - try fakesuper send and restore of single test file to ensure that xattrs can be stored
        // if not, ask if they can login as root
        // if not, warn user that backup and restore will take longer than necessary until they can enable xattrs for
        // the destination filesystem OR they can login as root

        // val one = s"mkdir -p $mountDir" !!
        // val two = s"sudo sshfs root@backup:/mnt/tank/backup/lune-rsnapshot/backup/localhost/ $mountDir" !!

        // backup

        val defaultExcludes = Seq("/proc", "/sys",
          "/dev", "/run", "/etc/mtab", "/media", "/net", "/home/.ecryptfs",
          "/var/cache/apt/archives", "/lost+found",
          "/tmp", "/var/tmp", "/var/backups", "/facsimile-sshfs")

        val defaultHomeExcludes = Seq(".gvfs", ".cache", ".thumbnails", "Trash", "trash", ".Private",
          ".backup", ".dropbox")

        val customExcludes = Seq("/backup", "/backupmount", "/sshfs", "/var/lib/mlocate", "/home/traack/.recoll/xapiandb", "/home/traack/.gconf.old/system/networking/connections", "/home/traack/.local/share/zeitgeist.old")

        val absoluteHomeExcludes: Seq[String] = Process("cut -d: -f6,7 /etc/passwd").lineStream.
          map(_.split(":")).
          filter(tuple => !Seq("/bin/false", "/usr/sbin/nologin", "/bin/sync").contains(if (tuple.length > 1) tuple(1) else "")).
          map(_(0)).
          flatMap(homedir => defaultHomeExcludes.map(exclude => s"$homedir/$exclude"))
        val allExcludes = (absoluteHomeExcludes ++ defaultExcludes ++ customExcludes)

        progressNotifier(0)
        // -M--fake-super to write user / group information into xattrs
        // --inplace to not re-write destination file (preserves bits for destination COW)

        // TODO - first time connecting to host, run
        // ssh-keyscan -H 192.168.147.30 >> /var/lib/facsimile/.ssh/known_hosts
        // ssh-keyscan -H transmission >> /var/lib/facsimile/.ssh/known_hosts
        // and then unique the ~/.ssh/known_hosts file

        val remote = config match {
          // TODO - will have to add case for other configs, for now it will explode if we attempt to use others
          case x: RemoteConfiguration => x
        }

        val fixedPath = config.target match {
          case x: FixedPath => x
        }

        val remoteHostDestination = s"${remote.user}@${remote.host}:${fixedPath.path}"

        Encryption.withEncryptedDir(sourceBackupDir) {
          case (encryptedDir, encryptionConfig) =>
            val previousManualSnapshot = getPreviousManualSnapshot(remote)

            val backupMessage = copy(encryptedDir, remoteHostDestination, allExcludes ++ Seq(encryptedDir), previousManualSnapshot, progressNotifier)

            val copyEncryptedConfigurationMessage = backupMessage.flatMap { s =>
              Try { ShellUtils.runCommand(s"sudo nice -n 19 rsync -aHAXvv $encryptionConfig $remoteHostDestination/encryption_config", "Could not copy encryption configuration file") }
            }

            val logCopyMessage = copyEncryptedConfigurationMessage.flatMap { s =>
              // snapshot
              val snapshotInstant = Instant.now()
              // TODO - replace hardcoded dataset path with actual dataset path
              val dataset = "tank/backup/lune-rsnapshot"
              val command2 = s"ssh ${remote.user}@${remote.host} ${zfsPrefix} zfs snapshot ${dataset}@facsimile-${snapshotInstant.toString}"
              println(command2)
              Try { ShellUtils.runCommand(command2, "Could not take snapshot") }.flatMap { s =>
                cullSnapshots(snapshotInstant, remote)
              }
            }

            Files.delete(tempBackupLogPath)

            logCopyMessage.get
        }
      }
    }
  }

  private def getPreviousManualSnapshot(config: RemoteConfiguration): Option[String] = {
    // TODO - adjust when implementing manual (rsync link-dest-based snapshots)
    if (true == false) {
      snapshotTimes(config).map(_.toString).sortWith(_ > _).headOption
    } else {
      None
    }
  }

  private def copy(source: String, destination: String, allExcludes: Seq[String], previousManualSnapshot: Option[String], progressNotifier: (Int) => Unit): Try[Unit] = {
    val rsyncMessages = scala.collection.mutable.ArrayBuffer.empty[String]

    val incrementalPattern = """.*(ir-chk)=.*""".r
    val totalPattern = """.*to-chk=(\d+)\/(\d+).*""".r
    val uptodate = """.*(uptodate).*""".r
    val hidingfile = """.*(hiding file).*""".r
    val rsyncMessage = """rsync:\s(.*)""".r
    val excludeFilesPath = Files.createTempFile("facsimile", "config")

    // --inplace should not be used with manual snapshots
    val command = """sudo nice -n 19 rsync -aHAXvv -M--fake-super --progress --omit-link-times """ +
      s"""--delete --exclude-from=${excludeFilesPath.toFile.toString} --numeric-ids """ +
      s"""--delete-excluded ${previousManualSnapshot.map(s => s"--link-dest=../$s").getOrElse("--inplace")} """ +
      s"""$source/ $destination/backup/${if (previousManualSnapshot.isDefined) "in-progress" else ""}"""

    var completed: Long = 0
    var latestPercent: Int = 0
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

    val fw = new FileWriter(tempBackupLogPath.toFile().getAbsolutePath)

    val excludeMessage = Try {
      val encryptedExcludes = allExcludes.par.map(Encryption.encodePath(sourceBackupDir, _))
      Files.write(excludeFilesPath, encryptedExcludes.mkString(System.lineSeparator).getBytes)
    }
    excludeMessage.flatMap { s =>
      Try {
        // TODO - the rsync process can hang. this should be executed in some other thread and we should watch for log lines.
        // if there hasn't been some log line in, 5 minutes, kill the rsync process and start again.
        try {
          println(s"Running rsync command $command")
          Process(command).lineStream(ProcessLogger(line => { rsyncMessages += line; () })).foreach(line => {
            line match {
              case incremental @ (uptodate(_) | hidingfile(_) | incrementalPattern(_)) => { computePercent(completed + 1, totalFiles) }
              case totalPattern(toGo, newTotal) => { computePercent(newTotal.toLong - toGo.toLong, newTotal.toLong) }
              case rsyncMessage(message) => { rsyncMessages += message }
              case others: String => { fw.write(others); fw.write(System.lineSeparator) }
            }
          })
        } finally {
          fw.close()
        }
      }.recoverWith {
        case NonFatal(e) =>
          val errorRegex = """Nonzero exit code: (\d+)""".r
          e.getMessage match {
            case errorRegex(code) => {
              code.toInt match {
                // could not delete all files because of max delete OR
                // some files not transfered because they disappeared first - not a problem
                case 24 | 25 => Success("")
                case 23 => Success(s"Some files not transfered due to an error${System.lineSeparator}${rsyncMessages.mkString(System.lineSeparator)}")
                case other: Int => Failure(new RuntimeException(s"Backup error code $other: ${System.lineSeparator}${rsyncMessages.mkString(System.lineSeparator)}")) // non fatal
              }
            }
            case _ => Failure(new RuntimeException(s"Backup error:${System.lineSeparator}${rsyncMessages.mkString(System.lineSeparator)}"))
          }
      }.flatMap { s =>
        Try {
          Files.write(totalPath, completed.toString.getBytes)

          val command = s"""sudo nice -n 19 rsync -aHAXvv --inplace --omit-link-times --numeric-ids ${tempBackupLogPath.toFile().getAbsolutePath} $destination/log"""

          println(command)

          command.!
          ()
        }
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

  def snapshots(tempConfig: Configuration): Map[String, String] = {
    val current = Instant.now()
    val oneDayBack = current.minus(1, ChronoUnit.DAYS)

    val dayFormatter = DateTimeFormatter.ofPattern("MMMM d, YYYY")
      .withLocale(Locale.ENGLISH)
      .withZone(ZoneId.systemDefault())

    snapshotTimes(tempConfig match { case x: RemoteConfiguration => x })
      .sortWith(_.toString < _.toString)
      .map(x => (x.toString, if (x.isBefore(oneDayBack)) { dayFormatter.format(x) } else { formatHour(x) }))
      .toMap
  }

  private def snapshotTimes(tempConfig: RemoteConfiguration): Seq[Instant] = {
    // TODO - detect dataset
    val dataset = "tank/backup/lune-rsnapshot"
    val length = (dataset + "@facsimile-").length
    Try {
      ShellUtils.runCommand(
        s"ssh ${tempConfig.user}@${tempConfig.host} ${zfsPrefix} zfs list -t snapshot -r $dataset",
        "Could not get snapshot list"
      )
    }.map { output =>
      output.split(System.lineSeparator).flatMap { str => Try { Instant.parse(str.substring(length, length + 24)) }.toOption }
    }.get
  }

  private def cullSnapshots(currentSnapshot: Instant, tempConfig: RemoteConfiguration): Try[Unit] = {
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
            case x: LocalDateTime if x.isAfter(oneDayBack) => "hour" + x.getHour
            case x: LocalDateTime if x.isBefore(oneDayBack) && x.isAfter(oneMonthBack) => "day" + x.getDayOfMonth
            case x: LocalDateTime => "year" + x.getYear + "week" + x.get(weekField)
          }

          if (set.contains(bucket)) {
            // TODO - replace hardcoded dataset path with actual dataset path
            val dataset = "tank/backup/lune-rsnapshot"
            val command = s"ssh ${tempConfig.user}@${tempConfig.host} zfs destroy ${dataset}@facsimile-$snapshotDate"
            println(s"deleting snapshot $snapshotDate with $command")
            command !!
          } else {
            println(s"found new element for bucket $bucket")
            set += bucket
          }
        })
    }
  }

  def getSnapshotFiles(snapshot: String, directory: String, tempConfig: Configuration): Seq[SnapshotFile] = {
    val fixedPath = tempConfig.target match { case x: FixedPath => x }
    var fileList = false
    val startLine = """\[(R)eceiver\]\sflist\sstart.*""".r
    val endLine = """(r)ecv_file_list\sdone""".r
    val fileLine = """\[Receiver\]\si=\d+\s\d\s(.+)\smode=(\d+)\slen=(\S+)\suid=(\d+)\sgid=(\d+)\sflags=(\d+)""".r

    Try {
      val actualDirectory = if (directory == "/") {
        "/"
      } else {
        Encryption.encodePath(sourceBackupDir, directory)
      }
      val remote = tempConfig match { case x: RemoteConfiguration => x }
      val command = s"sudo nice -n 19 rsync --dry-run -lptgoDHAXvvvv --dirs -M--fake-super --numeric-ids ${remote.user}@${remote.host}:${fixedPath.path}/.zfs/snapshot/facsimile-$snapshot/backup/$actualDirectory/ /tmp/Desktop/"
      Process(command).
        // TODO - do not ignore errors
        // TODO - if the directory which is being listed does not have r and x for the user running facsimile, then refuse to go into directory
        lineStream(ProcessLogger(_ => {})).flatMap {
          case startLine(x) if !fileList => { fileList = true; None }
          case endLine(x) if fileList => { fileList = false; None }
          case fileLine(name, mode, len, uid, gid, flags) if fileList => Some(SnapshotFile(name, uid.replace(",", "").toInt, Some(1234), isDir(mode)))
          case _ => None
        }.par.filter(_.name != "./").map { file =>
          val fullPath = s"${actualDirectory.replaceAll("/$", "")}/${file.name}"
          file.copy(name = Encryption.decodePath(sourceBackupDir, fullPath).replaceAll("/$", "").replaceFirst(".*/(\\S+)", "$1"))
        }.seq
    } match {
      case Success(x) => x
      case Failure(e) => Seq()
    }
  }

  private def isDir(mode: String): Boolean = {
    val regex = """(\d)?(\d)\d{5}\d{9}""".r
    Integer.toBinaryString(Integer.parseInt(mode, 8)) match {
      case regex(null, dir) if (dir == "1") => true
      case regex(file, dir) if (file == "0" && dir == "1") => true
      case regex(file, dir) if (file == "1" && dir == "0") => false
      case other: String => throw new RuntimeException(s"Unexpected file and directory bits $other")
    }
  }

  def restoreSnapshotFiles(config: Configuration, snapshot: String, backupPath: String, restorePath: String): Unit = {
    // 1) create restorePath as a directory
    Files.createDirectories(Paths.get(restorePath))

    val tempLocalRestorePath = Files.createTempDirectory("facsimile-temprestorepath")

    // 3) rsync
    val restore = Try {
      val encodedPath = Encryption.encodePath(sourceBackupDir, backupPath)

      System.out.println(s"source backup dir: $sourceBackupDir; backup path $backupPath; encoded path $encodedPath")
      val (tempLocalBackupPath, tempEncryptionConfigFile) = config match {
        case rc: RemoteConfiguration => {
          val tempEncryptionConfigFile = Files.createTempFile("facsimile-encryptionconfig", "file")

          // 2) decrypted mount from a new temp backup directory to temp restore directory
          val tempLocalBackupPath = Files.createTempDirectory("facsimile-tempbackuppath")
          new java.io.File(tempLocalBackupPath.toString)

          val fixedPath = config.target match { case x: FixedPath => x }

          val command = s"""sudo nice -n 19 rsync -aHAXvv -M--fake-super --inplace --progress --omit-link-times --numeric-ids ${rc.user}@${rc.host}:${fixedPath.path}/.zfs/snapshot/facsimile-$snapshot/backup/$encodedPath $tempLocalBackupPath/"""

          System.out.println("about to run sudo nice -n 19 zfs list -t snapshot")
          System.out.println(Process("sudo nice -n 19 zfs list -t snapshot").lineStream.mkString(" "))
          System.out.println(s"about to run sudo ls -al ${fixedPath.path}/.zfs/snapshot/facsimile-$snapshot/backup")
          System.out.println(Process(s"sudo ls -al ${fixedPath.path}/.zfs/snapshot/facsimile-$snapshot/backup").lineStream.mkString(System.lineSeparator))
          System.out.println(s"about to run $command")
          System.out.println(Process(command).lineStream.mkString(" "))

          val command2 = s"""sudo nice -n 19 rsync -aHAXvv -M--fake-super --inplace --progress --omit-link-times --numeric-ids ${rc.user}@${rc.host}:${fixedPath.path}/.zfs/snapshot/facsimile-$snapshot/encryption_config $tempEncryptionConfigFile"""

          System.out.println(s"about to run $command2")
          System.out.println(Process(command2).lineStream.mkString(" "))

          (tempLocalBackupPath, tempEncryptionConfigFile)
        }
      }

      try {
        Encryption.withDecryptedDir(tempLocalBackupPath.toString, tempEncryptionConfigFile.toString) {
          case (tempLocalRestorePath, encryptionConfig) =>
            val command3 = s"""sudo nice -n 19 rsync -aHAXvv --progress --omit-link-times --numeric-ids $tempLocalRestorePath/ $restorePath/"""

            System.out.println(s"about to run $command3")
            System.out.println(Process(command3).lineStream.mkString(" "))
        }
      } finally {
        // 4) remove decrypted mount
        ShellUtils.runCommand(s"sudo rm -rf $tempEncryptionConfigFile $tempLocalBackupPath $tempLocalRestorePath", "Could not remove temporary directories")
        ()
      }
    }

    // TODO - if restoring full system, make sure root of destination is mounted elsewhere on FS, not root
    restore.get
  }
}
