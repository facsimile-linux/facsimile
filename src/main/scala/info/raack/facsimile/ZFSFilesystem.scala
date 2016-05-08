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

// check for presence of zfs - https://github.com/zfsonlinux/pkg-zfs/wiki/HOWTO-install-Ubuntu-to-a-Native-ZFS-Root-Filesystem

// create target zfs if snapshots are not built in with target system
// dd if=/dev/zero of=m1  bs=1G  count=1
// dd if=/dev/zero of=m2  bs=1G  count=1
// sudo zpool create traackbackup mirror /home/traack/testbackup/m1 /home/traack/testbackup/m2

case class ZFSFilesystem(name: String, path: Option[String], alwaysMounted: Boolean) extends SnapshotFilesystem
    with PipedTransferSupported {
  def pipedTransferType(): String = {
    val me = Seq("lots of code","which","does other things","zf")
    val you = me.map(_ + "s") 
    val done = you.tail.tail.tail
    "zfs"
  }
}
