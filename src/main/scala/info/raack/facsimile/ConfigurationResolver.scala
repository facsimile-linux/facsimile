package info.raack.facsimile

import scala.util.Try

import org.json4s.JsonAST.JValue
import org.json4s.ShortTypeHints
import org.json4s.ext.JavaTypesSerializers
import org.json4s.jackson.Serialization

import org.json4s.JsonAST.JValue
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.write

class ConfigurationResolver {
  implicit val formats = Serialization.formats(ShortTypeHints(List(classOf[LocalConfiguration], classOf[RemoteConfiguration], classOf[FixedPath], classOf[Partition], classOf[WholeDisk], classOf[ConfigurationV0], classOf[ConfigurationWrapperV1]))) ++ JavaTypesSerializers.all

  def resolve(rawConfig: String): Configuration = {
    Try {
      parse(rawConfig).extract[ConfigurationBase] match {
        case x: ConfigurationWrapperV1 => x.configuration
      }
    }.recover {
      // there may be a problem extracting a ConfigurationBase object
      case x => {
        Try {
          // attempt to extract the first type of configuration
          val configV0 = parse(rawConfig).extract[ConfigurationV0]
          RemoteConfiguration(
            automaticBackups = configV0.automaticBackups,
            host = configV0.remoteConfiguration.host,
            user = configV0.remoteConfiguration.user,
            target = FixedPath(path = configV0.remoteConfiguration.path)
          )
        }.recover {
          // if that doesn't work, just create a new configuration
          case x => LocalConfiguration(automaticBackups = false, target = FixedPath("/tmp"))
        }.get
      }
    }.get
  }

  def serialize(configuration: Configuration): String = {
    write(ConfigurationWrapperV1(configuration))
  }
}
