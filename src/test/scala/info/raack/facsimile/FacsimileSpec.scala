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

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, File}
import java.io.PrintStream

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoField

import scala.util.Try
import scala.sys.process.stringToProcess
import scala.sys.process.Process

import org.json4s.jackson.JsonMethods.parse
import org.scalatest.FeatureSpec
import org.scalatest.GivenWhenThen

class FacsimileSpec extends FeatureSpec with GivenWhenThen {

  def runFacsimile(processor: FacsimileCLIProcessor, args: Array[String], input: Option[String] = None): (Int, String) = {
    val baos = new ByteArrayOutputStream
    val ps = new PrintStream(baos)
    val out = Console.withOut(ps)(Console.withErr(ps)(processor.process(args)))
    (out, baos.toString())
  }

  info("As a Facsimile user")
  info("I want to be run backups")
  info("So I can ensure that my data is safe in case of data loss")

  feature("Facsimile") {
    scenario("User requests help") {

      Given("a command line instance is available")

      When("the help command is given")

      Then("help should be printed out")
      val help = """
Usage: facsimile [COMMAND]
Run Facsimile command specified as COMMAND, or enter the Facsimile shell if COMMAND is not specified.

Possible values for COMMAND
  backup
      complete a backup using the current settings
  schedule-on
      turn on scheduled backups
  schedule-off
      turn off scheduled backups
  scheduled-backup
      if a backup is required (schedule is on and enough time has past so backups are less than 25% of wall time)
  list-snapshots
      print out all snapshots taken so far
  list-snapshot-files <snapshot> <directory>
      list all files in directory in snapshot
  restore-snapshot-files <snapshot> <directory> <restore directory>
      restore files from directory in snapshot into restore directory
  get-configuration
      print out the current backup configuration
  set-configuration
      store the configuration from standard in
  help                       print this help
  exit                       exit Facsimile

"""

      assertResult((0, help)) { runFacsimile(new FacsimileCLIProcessor(), Array("help")) }
    }
  }

  def testReadWriteConfig(inputConfig: String, givenWhenThen: Boolean = true): Unit = {
    testReadWriteConfigDifferent(inputConfig, inputConfig, givenWhenThen)
  }

  def setFlexCache(): Unit = {
    val dir = Files.createTempDirectory("facsimile-cachepath")
    new File(dir.toString()).deleteOnExit()
    System.setProperty("testingCacheDir", dir.toString)
  }

  def testReadWriteConfigDifferent(inputConfig: String, outputConfig: String, givenWhenThen: Boolean = true): Unit = {
    val dir = Files.createTempDirectory("facsimile-temppath")
    new File(dir.toString()).deleteOnExit()
    System.setProperty("testingConfigDir", dir.toString)

    if (givenWhenThen) {
      Given("a command line instance is available")
      When("remote configuration is written")
      Then("it is accepted correctly")
    }

    val bais = new ByteArrayInputStream(inputConfig.getBytes())

    assertResult((0, "")) { runFacsimile(new FacsimileCLIProcessor(bais), Array("set-configuration")) }

    val output = s"$outputConfig\n"

    When("configuration is re-read")
    Then("it matches what was provided")
    // it is critical to NOT re-use the existing FacsimileCLIProcessor to ensure that config doesn't get cached from
    // the previous set operation above
    assertResult((0, output)) { runFacsimile(new FacsimileCLIProcessor(bais), Array("get-configuration")) }
  }

  feature("Configuration") {

    scenario("Gets empty configuration") {
      val dir = Files.createTempDirectory("facsimile-temppath")
      new File(dir.toString()).deleteOnExit()
      System.setProperty("testingConfigDir", dir.toString)

      Given("a command line instance is available")

      When("configuration is requested")

      Then("configuration should be printed out")
      val output = """{"jsonClass":"ConfigurationWrapperV1","configuration":{"jsonClass":"LocalConfiguration","automaticBackups":false,"target":{"jsonClass":"FixedPath","path":"/tmp"}}}
"""

      assertResult((0, output)) { runFacsimile(new FacsimileCLIProcessor(), Array("get-configuration")) }
    }

    scenario("Writes and re-reads v1 local fixed-path configuration") {
      testReadWriteConfig("""{"jsonClass":"ConfigurationWrapperV1","configuration":{"jsonClass":"LocalConfiguration","automaticBackups":false,"target":{"jsonClass":"FixedPath","path":"/test"}}}""")
    }

    scenario("Writes and re-reads v1 local partition configuration") {
      testReadWriteConfig("""{"jsonClass":"ConfigurationWrapperV1","configuration":{"jsonClass":"LocalConfiguration","automaticBackups":false,"target":{"jsonClass":"Partition","id":"the-other-id"}}}""")
    }

    scenario("Writes and re-reads v1 local whole disk configuration") {
      testReadWriteConfig("""{"jsonClass":"ConfigurationWrapperV1","configuration":{"jsonClass":"LocalConfiguration","automaticBackups":false,"target":{"jsonClass":"WholeDisk","uuid":"a29ef9fa-9449-4ebf-a43e-4173d7e0c3d9"}}}""")
    }

    scenario("Writes and re-reads v1 remote fixed-path configuration") {
      testReadWriteConfig("""{"jsonClass":"ConfigurationWrapperV1","configuration":{"jsonClass":"RemoteConfiguration","automaticBackups":false,"host":"abcd","user":"testuser","target":{"jsonClass":"FixedPath","path":"/blah"}}}""")
    }

    scenario("Writes and re-reads v1 remote partition configuration") {
      testReadWriteConfig("""{"jsonClass":"ConfigurationWrapperV1","configuration":{"jsonClass":"RemoteConfiguration","automaticBackups":false,"host":"abcd","user":"testuser","target":{"jsonClass":"Partition","id":"this-is-the-id"}}}""")
    }

    scenario("Writes and re-reads v1 remote whole disk configuration") {
      testReadWriteConfig("""{"jsonClass":"ConfigurationWrapperV1","configuration":{"jsonClass":"RemoteConfiguration","automaticBackups":false,"host":"abcd","user":"testuser","target":{"jsonClass":"WholeDisk","uuid":"f20f81de-b563-494f-905d-99bf98e23c13"}}}""")
    }

    scenario("Upgrades version 1 to version 2") {
      testReadWriteConfigDifferent(
        """{"automaticBackups":true,"remoteConfiguration":{"host":"theremotehost","user":"testuser","path":"/some/long/path"},"configurationType":"remote"}""",
        """{"jsonClass":"ConfigurationWrapperV1","configuration":{"jsonClass":"RemoteConfiguration","automaticBackups":true,"host":"theremotehost","user":"testuser","target":{"jsonClass":"FixedPath","path":"/some/long/path"}}}"""
      )
    }
  }

  feature("backup / restore") {
    scenario("Can backup to location") {
      Given("an ssh fixed-path configuration")
      setFlexCache()

      val theuser = System.getProperty("user.name")

      val dir = Files.createTempDirectory("facsimile-backuppath")
      new File(dir.toString()).deleteOnExit()

      val configDir = Files.createTempDirectory("facsimile-configpath")
      new File(configDir.toString()).deleteOnExit()
      System.setProperty("testingConfigDir", configDir.toString)

      System.setProperty("testingFacsimileShareDir", "pwd".lineStream.mkString("") + "/src/main/shell")
      System.setProperty("testingSnapshotPrefix", "sudo")

      // initialize ZFS filesystem for snapshots
      // clean up in case last run did not for some reason
      "sudo zpool export tank".lineStream_!
      "rm /tmp/zfsfile".lineStream_!
      assertResult(0)("fallocate -l 500M /tmp/zfsfile".!)
      Try {
        "sudo zpool create tank /tmp/zfsfile".!!
        "sudo zfs create tank/backup".!!
        "sudo zfs create tank/backup/lune-rsnapshot".!!
        "sudo zfs snapshot tank/backup/lune-rsnapshot@test".!!
        "sudo ls -al /tank/backup/lune-rsnapshot/.zfs/snapshot/test".!
        s"sudo chown $theuser /tank/backup/lune-rsnapshot".!!
        s"sudo zfs allow $theuser mount,destroy tank/backup/lune-rsnapshot".!!
        "sudo rsync -aHAXv /bin/ /tmp/sourcebin/".!!

        // write configuration for this ZFS remote
        Files.write(
          Paths.get(configDir.toString, "config"),
          s"""{"jsonClass":"ConfigurationWrapperV1","configuration":{"jsonClass":"RemoteConfiguration","automaticBackups":false,"host":"localhost","user":"$theuser","target":{"jsonClass":"FixedPath","path":"/tank/backup/lune-rsnapshot"}}}""".getBytes
        )

        Files.write(Paths.get("src/main/shell/facsimile-password"), s"""#!/usr/bin/env bash\n\ncat ${Paths.get(configDir.toString, "password")}\n""".getBytes)
        Files.write(Paths.get(configDir.toString, "password"), "thepassword".getBytes)
        System.setProperty("testingSourceDir", "/tmp/sourcebin")

        // run the backup
        When("backup is requested")

        Then("it backs up files correctly")
        assertResult(0) { runFacsimile(new FacsimileCLIProcessor(), Array("backup"))._1 }

        // verify that a single snapshot exists
        When("the list of snapshots are requested")
        val output = runFacsimile(new FacsimileCLIProcessor(), Array("list-snapshots"))

        Then("a list of snapshots is retured correctly")
        implicit val formats = org.json4s.DefaultFormats
        assert(0 == output._1)
        val snapshots = parse(output._2).extract[Map[String, String]]
        assert(1 == snapshots.keys.size)
        val date = ZonedDateTime.parse(snapshots.keys.head, DateTimeFormatter.ISO_DATE_TIME)
        val now = ZonedDateTime.now()
        assert(now.minusSeconds(10).isBefore(date))
        assert(date.isBefore(now))
        assertResult(s"Today, ${now.format(DateTimeFormatter.ofPattern("h:mm a"))}")(snapshots.values.head)

        // verify that listing snapshot files works
        When("a list of files in a snapshot directory is requested")
        val listOutput = runFacsimile(new FacsimileCLIProcessor(), Array("list-snapshot-files", snapshots.keys.head, "/"))

        Then("some files are present")
        assert(0 == listOutput._1)
        val files = parse(listOutput._2).extract[Seq[SnapshotFile]]
        assert(154 == files.size)
        val umountOption = files.find(_.name == "umount")
        assert(umountOption.isDefined)
        val umount = umountOption.get
        assert(!umount.isDirectory)
        assert(umount.ownerId == 0)
        assert(umount.sizeInBytes == Some(1234))

        // verify that retrieving one snapshot file works
        When("a restoring one file in a snapshot directory is requested")
        val restoreDir1 = Files.createTempDirectory("facsimile-restorepath1")
        new File(restoreDir1.toString()).deleteOnExit()
        val restoreOutput = runFacsimile(new FacsimileCLIProcessor(), Array("restore-snapshot-files", snapshots.keys.head, "/umount", restoreDir1.toString))

        Then("the one file is restored")
        assert(0 == restoreOutput._1)
        // ensure restored file is identical
        assert(s"sudo rsync -avHAX /bin/umount ${restoreDir1.toString}/umount".lineStream.mkString("\n").contains("sending incremental file list\n\nsent"))

        // verify that retrieving all snapshot files works
        When("a restoring all files in a snapshot directory is requested")
        val restoreDir2 = Files.createTempDirectory("facsimile-restorepath2")
        new File(restoreDir2.toString()).deleteOnExit()
        val restoreOutput2 = runFacsimile(new FacsimileCLIProcessor(), Array("restore-snapshot-files", snapshots.keys.head, "/", restoreDir2.toString))

        Then("all files are restored")
        assert(0 == restoreOutput2._1)
        // ensure restored files are identical
        System.out.println("original dir")
        "sudo ls -al /bin".!
        System.out.println("restored dir")
        s"sudo ls -al ${restoreDir2.toString}".!
        assert(s"sudo rsync -avHAX /bin/ ${restoreDir2.toString}/".lineStream.mkString("\n").contains("sending incremental file list\n./\n\nsent"))

      } match {
        case e =>
          "sudo zpool export tank".!
          "rm /tmp/zfsfile".!
          "sudo rm -rf /tmp/sourcebin".!
          e.get
      }
    }
  }
}
