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
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

package org.apache.spark

import org.apache.spark.memory.MemoryManager
import org.apache.spark.util.Utils


object SparkSnappyUtils {

  val SNAPPY_UNIFIED_MEMORY_MANAGER_CLASS = "org.apache.spark.memory.SnappyUnifiedMemoryManager"
  val SNAPPY_DATA_FUNCTIONS_CLASS = "io.snappydata.SnappyDataFunctions"
  val SNAPPY_ENT_FUNCTIONS_CLASS = "org.apache.spark.sql.execution.SnappyContextAQPFunctions"

  def loadSnappyManager(conf: SparkConf, numUsableCores: Int): Option[MemoryManager] = {

    try {
      Some(Utils.classForName(SNAPPY_UNIFIED_MEMORY_MANAGER_CLASS)
          .getConstructor(classOf[SparkConf], classOf[Int])
          .newInstance(conf, Int.box(numUsableCores))
          .asInstanceOf[MemoryManager])
    } catch {
      case _: ClassNotFoundException => None
    }
  }

  /**
   * This will return Seq[(String, ExpressionInfo, FunctionBuilder)]
   */
  def additionalBuiltinFunctions: Seq[Any] = {
    val baseFunctions = try {
      val functionsClass = Utils.classForName(SNAPPY_DATA_FUNCTIONS_CLASS)
      val builtin = functionsClass.getMethod("builtin")
      builtin.setAccessible(true)
      builtin.invoke(null).asInstanceOf[Seq[Any]]
    } catch {
      case _: Exception => Nil
    }
    val entFunctions = try {
      val functionsClass = Utils.classForName(SNAPPY_ENT_FUNCTIONS_CLASS)
      val builtin = functionsClass.getMethod("builtin")
      builtin.setAccessible(true)
      builtin.invoke(null).asInstanceOf[Seq[Any]]
    } catch {
      case _: Exception => Nil
    }
    baseFunctions ++ entFunctions
  }
}
