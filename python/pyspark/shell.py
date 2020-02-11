#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
#
# Changes for TIBCO Project SnappyData data platform.
#
# Portions Copyright (c) 2017-2020 TIBCO Software Inc. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you
# may not use this file except in compliance with the License. You
# may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
# implied. See the License for the specific language governing
# permissions and limitations under the License. See accompanying
# LICENSE file.

"""
An interactive shell.

This file is designed to be launched as a PYTHONSTARTUP script.
"""

import atexit
import os
import platform
import warnings

import py4j

from pyspark import SparkConf
from pyspark.context import SparkContext
from pyspark.sql import SparkSession, SQLContext
from pyspark.sql.snappy import SnappySession

if os.environ.get("SPARK_EXECUTOR_URI"):
    SparkContext.setSystemProperty("spark.executor.uri", os.environ["SPARK_EXECUTOR_URI"])

SparkContext._ensure_initialized()

conf = SparkConf()
catalogImplementation = conf.get('spark.sql.catalogImplementation', 'hive').lower()
try:
    spark = SparkSession._create_shell_session()
except Exception:
    import sys
    import traceback
    warnings.warn("Failed to initialize Spark session.")
    traceback.print_exc(file=sys.stderr)
    sys.exit(1)

sc = spark.sparkContext
if catalogImplementation == 'in-memory':
    snappy = SnappySession(sc)
    sql = snappy.sql
else:
    sql = spark.sql
atexit.register(lambda: sc.stop())

# for compatibility
if catalogImplementation == 'in-memory':
    sqlContext = snappy._wrapped
else:
    sqlContext = spark._wrapped
sqlCtx = sqlContext

print(r"""Welcome to
      ____              __
     / __/__  ___ _____/ /__
    _\ \/ _ \/ _ `/ __/  '_/
   /__ / .__/\_,_/_/ /_/\_\   version %s
      /_/
""" % sc.version)
print("Using Python version %s (%s, %s)" % (
    platform.python_version(),
    platform.python_build()[0],
    platform.python_build()[1]))
print("SparkSession available as 'spark'.")
if catalogImplementation == 'in-memory':
    print("SnappySession available as 'snappy'.")

# The ./bin/pyspark script stores the old PYTHONSTARTUP value in OLD_PYTHONSTARTUP,
# which allows us to execute the user's PYTHONSTARTUP file:
_pythonstartup = os.environ.get('OLD_PYTHONSTARTUP')
if _pythonstartup and os.path.isfile(_pythonstartup):
    with open(_pythonstartup) as f:
        code = compile(f.read(), _pythonstartup, 'exec')
        exec(code)
