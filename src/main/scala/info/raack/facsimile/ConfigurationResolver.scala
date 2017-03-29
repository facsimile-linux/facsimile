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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.util.Try

import org.json4s.JsonAST.JValue
import org.json4s.ShortTypeHints
import org.json4s.ext.JavaTypesSerializers
import org.json4s.jackson.Serialization

import org.json4s.JsonAST.JValue
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.write

class ConfigurationResolver(configDir: Path) {
  implicit val formats = Serialization.formats(ShortTypeHints(List(classOf[LocalConfiguration], classOf[RemoteConfiguration], classOf[LocalConfigurationV2], classOf[RemoteConfigurationV2], classOf[FixedPath], classOf[Partition], classOf[WholeDisk], classOf[ConfigurationV0], classOf[ConfigurationWrapperV1], classOf[ConfigurationWrapperV2]))) ++ JavaTypesSerializers.all

  private val passwordPath = Paths.get(configDir.toString, "password")

  def resolve(rawConfig: String): Configuration = {
    Try {
      parse(rawConfig).extract[ConfigurationBase] match {
        case x: ConfigurationWrapperV2 => x.configuration
        case y: ConfigurationWrapperV1 => y.configuration match {
          case me: LocalConfiguration =>
            LocalConfigurationV2(
              automaticBackups = me.automaticBackups,
              target = me.target,
              encryptionKey = new String(Files.readAllBytes(passwordPath))
            )
          case me: RemoteConfiguration =>
            RemoteConfigurationV2(
              automaticBackups = me.automaticBackups,
              host = me.host,
              user = me.user,
              target = me.target,
              encryptionKey = new String(Files.readAllBytes(passwordPath))
            )
        }
      }
    }.recover {
      case x => {
        Try {
          // attempt to extract the first type of configuration
          val configV0 = parse(rawConfig).extract[ConfigurationV0]
          RemoteConfigurationV2(
            automaticBackups = configV0.automaticBackups,
            host = configV0.remoteConfiguration.host,
            user = configV0.remoteConfiguration.user,
            target = FixedPath(path = configV0.remoteConfiguration.path),
            encryptionKey = new String(Files.readAllBytes(passwordPath))
          )
        }.recover {
          case y =>
            LocalConfigurationV2(
              automaticBackups = false,
              target = FixedPath("/tmp"),
              encryptionKey = ""
            )
        }.get
      }
    }.get
  }

  def serialize(configuration: Configuration): String = {
    write(ConfigurationWrapperV2(configuration))
  }
}
