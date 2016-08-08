package info.raack.facsimile

case class SnapshotFile(name: String, ownerId: Int, sizeInBytes: Option[Int], isDirectory: Boolean)