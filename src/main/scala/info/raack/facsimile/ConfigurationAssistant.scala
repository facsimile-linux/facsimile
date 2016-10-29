package info.raack.facsimile

import scala.sys.process.stringToProcess
import scala.sys.process.Process

object ConfigurationAssistant {
  def testConfiguration(config: Configuration): ConfigurationTestResult = {
    //val sourceFilesystems: Traversable[Filesystem] = findSourceFilesystemCapabilities()
    //val destinationFilesystemCapabilities: Traversable[Filesystem] = findDestinationFilesystemCapabilities(config)
    //val canDestinationRepartition: Boolean = canDestintionPartition(config)

    // cannot select a disk on which any active mountpoints exist

    // local: would like to choose an entire disk
    // v1
    // source: one mount point (snapshotting); dest: manage whole disk - snapshot transfer, fallback to regular transfer then snapshots, fallback to regular transfer with
    // source: one mount point (snapshotting); dest: manage one partition only - snapshot transfer, fallback to regular transfer with snapshots, fallback to regular transfer with links

    // source: one mount point (non-snapshotting); dest: manage whole disk - regular transfer, snapshots, fall back to regular transfer with links
    // source: one mount point (non-snapshotting); dest: manage one partition only - regular transfer, snapshots, fallback to regular transfer with links

    // source: multiple mount points (all snapshotting); dest: manage whole disk - use single partition, snapshot transfer
    // source: multiple mount points (all snapshotting); dest: manage one partition only - use single partition, snapshot transfer
    // source: multiple mount points (some snapshotting); dest: manage whole disk - one partition for snapshotting systems,
    // source: multiple mount points (none snapshotting); 

    // remote: would like to choose an entire disk
    // v1
    // source: one mount point (snapshotting); dest: whole disk - snapshot transfer, fallback to regular transfer then snapshots, fallback to regular transfer with
    // source: one mount point (snapshotting); dest: one partition only - snapshot transfer, fallback to regular transfer with snapshots, fallback to regular transfer with links
    // source: one mount point (snapshotting); dest: one path only - snapshot transfer, fallback to regular transfer with snapshots, fallback 
    // source: one mount point (non-snapshotting); dest: whole disk - regular transfer, snapshots, fall back to regular transfer with links
    // source: one mount point (non-snapshotting); dest: one partition only - regular transfer, snapshots, fallback to regular transfer with links
    // source: multiple mount points (regardless if they are snapshotting or not); dest: whole disk - snapshotting, fallback to regular with links

    // based on responses, find most suitable backup option
    // also include suggestions to the user for improving backups, if they have not yet already been given

    null

  }

  private def findSourceFilesystems(): Traversable[Filesystem] = {
    None
  }

  private def findDestinationFilesystems(config: Configuration): Traversable[Filesystem] = {
    config match {
      case x: LocalConfiguration => None
      case x: RemoteConfiguration => None
    }
  }

  private case class Device(name: String, model: String, size: Long, activeMountpoints: Traversable[String])

  private def getLocalDevicesAvailable(): Traversable[Device] = {
    val output = Process("lsblk -n --output NAME,MOUNTPOINT,MODEL,SIZE,TYPE -P -b").lineStream.map { line =>
      """(\w*?)="(.*?)"""".r.findAllIn(line).matchData.map(pair => (pair.group(1) -> pair.group(2))).toMap
    }

    val mountPoints = output

      .filter(_("TYPE") == "disk")
      .map(data => Device(name = data("NAME"), model = data("MODEL"), size = data("BYTES").toLong, None))

    mountPoints
  }

  private def canDestinationPartition(config: Configuration): Boolean = {
    config match {
      case x: LocalConfiguration => true
      case x: RemoteConfiguration => false // TODO - check to see if we can do partitioning remotely, so that different filesystems can have their own partition
    }
  }

  // lookup remote snapshotting capabilities, preferrentially: ZFS, Rsync
  /*private def remoteSnapshotSystem(config: Configuration): Option[SnapshotManager] = {
    if(zfsRemote(config)) {
      Some(ZFS)
    } else if(rsyncRemote(config)) {
      Some(Rsync)
    } else {
      None
    }
  }
  
  private def zfsRemote(config: Configuration): Boolean = {
    remoteSnapshotSystem(config, ZFS)
  }
  
  private def rsyncRemote(config: Configuration): Boolean = {
    remoteSnapshotSystem(config, ZFS)
  }
  
  private def remoteSnapshotSystem(config: Configuration, snapshotSystem: SnapshotSystem): Boolean = {
    executeRemoteCommand(config, snapshotSystem).code == 0
  }*/
}