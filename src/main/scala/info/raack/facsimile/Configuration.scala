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

import java.util.UUID

sealed abstract class Target
case class WholeDisk(uuid: UUID) extends Target
case class Partition(id: String) extends Target
case class FixedPath(path: String) extends Target

sealed trait ConfigurationBase

// v0

sealed case class ConfigurationV0(var automaticBackups: Boolean, remoteConfiguration: RemoteConfigurationV0, configurationType: String)
  extends ConfigurationBase {}

sealed case class RemoteConfigurationV0(host: String, user: String, path: String)

// v1

case class ConfigurationWrapperV1(configuration: Configuration) extends ConfigurationBase {}

sealed trait Configuration {
  val automaticBackups: Boolean
  val target: Target
}

case class RemoteConfiguration(automaticBackups: Boolean, host: String, user: String, target: Target) extends Configuration

case class LocalConfiguration(automaticBackups: Boolean, target: Target) extends Configuration

// V2 - future

case class FilesystemCapability(nonSnapshotting: Boolean, zfs: Boolean, btrfs: Boolean)

case class FilesystemCapabilities(mountPointCapabilities: Map[String, FilesystemCapability])

case class ConfigurationTestResult(sourceFilesystemCapabilities: FilesystemCapabilities, destinationFilesystemCapabilities: FilesystemCapability, canDestinationRepartition: Boolean, improvementSuggestion: Option[String])
