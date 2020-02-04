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

package org.apache.spark.sql.execution.datasources.json

import java.io.CharArrayWriter

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.hadoop.io.{LongWritable, NullWritable, Text}
import org.apache.hadoop.mapred.{JobConf, TextInputFormat}
import org.apache.hadoop.mapreduce.{Job, RecordWriter, TaskAttemptContext}
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat

import org.apache.spark.internal.Logging
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.json.{JacksonGenerator, JacksonParser, JSONOptions}
import org.apache.spark.sql.catalyst.util.CompressionCodecs
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.execution.datasources.text.TextOutputWriter
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.SerializableConfiguration

class JsonFileFormat extends TextBasedFileFormat with DataSourceRegister {
  override val shortName: String = "json"

  override def isSplitable(
      sparkSession: SparkSession,
      options: Map[String, String],
      path: Path): Boolean = {
    val parsedOptions = new JSONOptions(
      options,
      sparkSession.sessionState.conf.columnNameOfCorruptRecord)
    val jsonDataSource = JsonDataSource(parsedOptions)
    jsonDataSource.isSplitable && super.isSplitable(sparkSession, options, path)
  }

  override def inferSchema(
      sparkSession: SparkSession,
      options: Map[String, String],
      files: Seq[FileStatus]): Option[StructType] = {

    val parsedOptions = new JSONOptions(
      options,
      sparkSession.sessionState.conf.columnNameOfCorruptRecord)
    JsonDataSource(parsedOptions).infer(
      sparkSession, files, parsedOptions)
  }

  override def prepareWrite(
      sparkSession: SparkSession,
      job: Job,
      options: Map[String, String],
      dataSchema: StructType): OutputWriterFactory = {
    val conf = job.getConfiguration

    val parsedOptions = new JSONOptions(
      options,
      sparkSession.sessionState.conf.columnNameOfCorruptRecord)
    parsedOptions.compressionCodec.foreach { codec =>
      CompressionCodecs.setCodecConfiguration(conf, codec)
    }

    new OutputWriterFactory {
      override def newInstance(
          path: String,
          dataSchema: StructType,
          context: TaskAttemptContext): OutputWriter = {
        new JsonOutputWriter(path, parsedOptions, dataSchema, context)
      }

      override def getFileExtension(context: TaskAttemptContext): String = {
        ".json" + TextOutputWriter.getCompressionExtension(context)
      }
    }
  }

  override def buildReader(
      sparkSession: SparkSession,
      dataSchema: StructType,
      partitionSchema: StructType,
      requiredSchema: StructType,
      filters: Seq[Filter],
      options: Map[String, String],
      hadoopConf: Configuration): PartitionedFile => Iterator[InternalRow] = {
    val broadcastedHadoopConf =
      sparkSession.sparkContext.broadcast(new SerializableConfiguration(hadoopConf))


    val parsedOptions = new JSONOptions(
      options,
      sparkSession.sessionState.conf.columnNameOfCorruptRecord)

    (file: PartitionedFile) => {
      val parser = new JacksonParser(requiredSchema, parsedOptions)
      JsonDataSource(parsedOptions).readFile(
        broadcastedHadoopConf.value.value,
        file,
        parser)
    }
  }

  override def toString: String = "JSON"

  override def hashCode(): Int = getClass.hashCode()

  override def equals(other: Any): Boolean = other.isInstanceOf[JsonFileFormat]
}

private[json] class JsonOutputWriter(
    path: String,
    options: JSONOptions,
    dataSchema: StructType,
    context: TaskAttemptContext)
  extends OutputWriter with Logging {

  private[this] val writer = new CharArrayWriter()
  // create the Generator without separator inserted between 2 records
  private[this] val gen = new JacksonGenerator(dataSchema, writer, options)
  private[this] val result = new Text()

  private val recordWriter: RecordWriter[NullWritable, Text] = {
    new TextOutputFormat[NullWritable, Text]() {
      override def getDefaultWorkFile(context: TaskAttemptContext, extension: String): Path = {
        new Path(path)
      }
    }.getRecordWriter(context)
  }

  override def write(row: Row): Unit = throw new UnsupportedOperationException("call writeInternal")

  override protected[sql] def writeInternal(row: InternalRow): Unit = {
    gen.write(row)
    gen.flush()

    result.set(writer.toString)
    writer.reset()

    recordWriter.write(NullWritable.get(), result)
  }

  override def close(): Unit = {
    gen.close()
    recordWriter.close(context)
  }
}
