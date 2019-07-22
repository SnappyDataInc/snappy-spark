/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Copyright (c) 2018 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package org.apache.spark.util

import java.io.File

import scala.io.Source
import scala.reflect.io.Path

import com.google.common.collect.Lists
import org.apache.commons.io.FileUtils

import org.apache.spark.internal.Logging

/**
 * Contains utility methods to manage spark local directories. This service is written for handling
 * of spark local directories left orphan due to scenario like abrupt failure of JVM.
 */
object LocalDirectoryCleanupService extends Logging {

  private val fileName = ".tempfiles.list"

  /**
   * Add new path to temporary file list.
   *
   * @param path path to temp file/directory
   */
  def add(path: Path): Unit = {
    FileUtils.writeLines(new File(fileName), "UTF-8",
      Lists.newArrayList(path.toString()), true)
  }

  /**
   * Attempts to recursively delete all files/directories available in temp files list in a fail
   * safe manner. Also cleans the temp files list once deletion is complete.
   */
  def clean(): Unit = {
    if (Path(fileName).exists) {
      Source.fromFile(fileName, "UTF-8").getLines().foreach(f => {
        try {
          if (Path(f).exists) {
            if (!Path(f).deleteRecursively()) {
              logWarning(s"There was some error while deleting file: $f")
            }
          } else {
            logInfo(s"$f does not exists.")
          }
        } catch {
          case ex: Exception => logWarning("There was some error while deleting file: $f", ex)
        }
      })
      Path(fileName).delete()
    }
  }
}