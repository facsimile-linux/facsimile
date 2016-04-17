/**
 * This file is part of Snappy.
 * 
 * (C) Copyright 2016 Taylor Raack.
 * 
 * Snappy is free software: you can redistribute it and/or modify
 * it under the terms of the Affero GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Snappy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Affero GNU General Public License for more details.
 * 
 * You should have received a copy of the Affero GNU General Public License
 * along with Snappy.  If not, see <http://www.gnu.org/licenses/>.
 */

package info.raack.snappy

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

import org.scalatest.FlatSpec

class SnappySpec extends FlatSpec {
  
  "Snappy" should "backup non-snapshotting filesystem to ZFS system and snapshot" in {
    
    val path = Files.createTempFile("snappy", "config")
    
    Files.write(path, Map(("dummy","one")).toString.getBytes)
    
    val snappy = new Snappy(path.toFile.getAbsolutePath)
    snappy.backup()
    
    //snappy.getSnapshots() should be Seq(Instant.now())
  }
}