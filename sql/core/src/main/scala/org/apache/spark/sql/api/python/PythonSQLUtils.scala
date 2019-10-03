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
 * Changes for TIBCO Project SnappyData data platform.
 *
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

package org.apache.spark.sql.api.python

import org.apache.spark.SparkSnappyUtils
import org.apache.spark.sql.catalyst.analysis.FunctionRegistry
import org.apache.spark.sql.catalyst.expressions.ExpressionInfo
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.types.DataType

private[sql] object PythonSQLUtils {
  def parseDataType(typeText: String): DataType = CatalystSqlParser.parseDataType(typeText)

  // This is needed when generating SQL documentation for built-in functions.
  def listBuiltinFunctionInfos(): Array[ExpressionInfo] = {
    // noinspection ConvertibleToMethodValue
    val base = FunctionRegistry.functionSet.flatMap(FunctionRegistry.builtin.lookupFunction(_))
    val extra = SparkSnappyUtils.additionalBuiltinFunctions
        .asInstanceOf[Seq[(String, ExpressionInfo, Any)]].map(_._2)
    if (extra.nonEmpty) (base ++ extra).toArray else base.toArray
  }
}
