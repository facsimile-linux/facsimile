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

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, File}
import java.io.PrintStream

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

import scala.util.Try
import scala.sys.process.stringToProcess
import scala.sys.process.Process

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
  backup                     complete a backup using the current settings
  schedule-on                turn on scheduled backups
  schedule-off               turn off scheduled backups
  scheduled-backup           if a backup is required (schedule is on and enough time has past so backups are less than 25% of wall time)
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
      setFlexCache()
      Given("a command line instance is available")

      When("backup is requested")

      Then("it backs up files in the desired location")

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
      "sudo zpool export tank".!
      "rm /tmp/zfsfile".!
      assertResult(0)("fallocate -l 500M /tmp/zfsfile".!)
      Try {
        assertResult(0)("sudo zpool create tank /tmp/zfsfile".!)
        assertResult(0)("sudo zfs create tank/backup".!)
        assertResult(0)("sudo zfs create tank/backup/lune-rsnapshot".!)
        assertResult(0)("sudo zfs set mountpoint=/tmp/testmount tank/backup/lune-rsnapshot".!)
        assertResult(0)(s"sudo chown $theuser /tmp/testmount".!)
        assertResult(0)(s"sudo zfs allow $theuser mount,destroy tank/backup/lune-rsnapshot".!)
        assertResult(0)("sudo rsync -aHAXv /bin/ /tmp/sourcebin/".!)

        // write configuration for this ZFS remote
        Files.write(
          Paths.get(configDir.toString, "config"),
          s"""{"jsonClass":"ConfigurationWrapperV1","configuration":{"jsonClass":"RemoteConfiguration","automaticBackups":false,"host":"localhost","user":"$theuser","target":{"jsonClass":"FixedPath","path":"/tmp/testmount"}}}""".getBytes
        )

        Files.write(Paths.get(configDir.toString, "password"), "thepassword".getBytes)
        System.setProperty("testingSourceDir", "/tmp/sourcebin")

        // run the backup
        assertResult(0) { runFacsimile(new FacsimileCLIProcessor(), Array("backup"))._1 }

        // TODO - verify that a single snapshot exists
        //assertResult((0, "the one snapshot")) { runFacsimile(new FacsimileCLIProcessor(), Array("list-snapshots")) }

        // TODO - verify that listing snapshot files works

        // TODO - verify that retrieving one snapshot file works

        // TODO - verify that retrieving all snapshot files works
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
