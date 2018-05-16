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

package org.apache.spark.scheduler

import java.io.{DataInputStream, DataOutputStream}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Properties

import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, KryoSerializable}
import org.apache.spark.util.{ByteBufferInputStream, ByteBufferOutputStream, SerializableBuffer, Utils}

import scala.collection.JavaConverters._
import scala.collection.mutable.{HashMap, Map}

/**
 * Description of a task that gets passed onto executors to be executed, usually created by
 * `TaskSetManager.resourceOffer`.
 *
 * TaskDescriptions and the associated Task need to be serialized carefully for two reasons:
 *
 *     (1) When a TaskDescription is received by an Executor, the Executor needs to first get the
 *         list of JARs and files and add these to the classpath, and set the properties, before
 *         deserializing the Task object (serializedTask). This is why the Properties are included
 *         in the TaskDescription, even though they're also in the serialized task.
 *     (2) Because a TaskDescription is serialized and sent to an executor for each task, efficient
 *         serialization (both in terms of serialization time and serialized buffer size) is
 *         important. For this reason, we serialize TaskDescriptions ourselves with the
 *         TaskDescription.encode and TaskDescription.decode methods.  This results in a smaller
 *         serialized size because it avoids serializing unnecessary fields in the Map objects
 *         (which can introduce significant overhead when the maps are small).
 */
private[spark] class TaskDescription(
    private var _taskId: Long,
    private var _attemptNumber: Int,
    private var _executorId: String,
    private var _name: String,
    private var _index: Int,    // Index within this task's TaskSet
    val addedFiles: Map[String, Long],
    val addedJars: Map[String, Long],
    val properties: Properties,
    @transient private var _serializedTask: ByteBuffer,
    private[spark] var taskData: TaskData = TaskData.EMPTY)
  extends Serializable with KryoSerializable {

  def taskId: Long = _taskId
  def attemptNumber: Int = _attemptNumber
  def executorId: String = _executorId
  def name: String = _name
  def index: Int = _index

  // Because ByteBuffers are not serializable, wrap the task in a SerializableBuffer
  private val buffer =
    if (_serializedTask ne null) new SerializableBuffer(_serializedTask) else null

  def serializedTask: ByteBuffer =
    if (_serializedTask ne null) _serializedTask else buffer.value

  override def write(kryo: Kryo, output: Output): Unit = {
    output.writeLong(_taskId)
    output.writeVarInt(_attemptNumber, true)
    output.writeString(_executorId)
    output.writeString(_name)
    output.writeInt(_index)
    output.writeInt(addedFiles.size)
    // Write files.
    for ((key, value) <- addedFiles) {
      output.writeString(key)
      output.writeLong(value)
    }
    // Write jars.
    output.writeInt(addedJars.size)
    for ((key, value) <- addedJars) {
      output.writeString(key)
      output.writeLong(value)
    }
    // Write properties.
    output.writeInt(properties.size())
    properties.asScala.foreach { case (key, value) =>
      output.writeString(key)
      // SPARK-19796 -- writeUTF doesn't work for long strings, which can happen for property values
      val bytes = value.getBytes(StandardCharsets.UTF_8)
      output.writeInt(bytes.length)
      output.write(bytes)
    }
    output.writeInt(_serializedTask.remaining())
    Utils.writeByteBuffer(_serializedTask, output)
    TaskData.write(taskData, output)
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    _taskId = input.readLong()
    _attemptNumber = input.readVarInt(true)
    _executorId = input.readString()
    _name = input.readString()
    _index = input.readInt()
    // Read files.
    val fileSize = input.readInt()
    for (_ <- 0 until fileSize) {
      addedFiles(input.readString()) = input.readLong()
    }
    // Read jars.
    val jarSize = input.readInt()
    for (_ <- 0 until jarSize) {
      addedJars(input.readString()) = input.readLong()
    }
    // Read properties.
    val properties = new Properties()
    val numProperties = input.readInt()
    for (_ <- 0 until numProperties) {
      val key = input.readString()
      val valueLength = input.readInt()
      val valueBytes = new Array[Byte](valueLength)
      input.read(valueBytes)
      properties.setProperty(key, new String(valueBytes, StandardCharsets.UTF_8))
    }
    val len = input.readInt()
    _serializedTask = ByteBuffer.wrap(input.readBytes(len))
    taskData = TaskData.read(input)
  }
}

private[spark] object TaskDescription {
  private def serializeStringLongMap(map: Map[String, Long], dataOut: DataOutputStream): Unit = {
    dataOut.writeInt(map.size)
    for ((key, value) <- map) {
      dataOut.writeUTF(key)
      dataOut.writeLong(value)
    }
  }

  def encode(taskDescription: TaskDescription): ByteBuffer = {
    val bytesOut = new ByteBufferOutputStream(4096)
    val dataOut = new DataOutputStream(bytesOut)

    dataOut.writeLong(taskDescription.taskId)
    dataOut.writeInt(taskDescription.attemptNumber)
    dataOut.writeUTF(taskDescription.executorId)
    dataOut.writeUTF(taskDescription.name)
    dataOut.writeInt(taskDescription.index)

    // Write files.
    serializeStringLongMap(taskDescription.addedFiles, dataOut)

    // Write jars.
    serializeStringLongMap(taskDescription.addedJars, dataOut)

    // Write properties.
    dataOut.writeInt(taskDescription.properties.size())
    taskDescription.properties.asScala.foreach { case (key, value) =>
      dataOut.writeUTF(key)
      // SPARK-19796 -- writeUTF doesn't work for long strings, which can happen for property values
      val bytes = value.getBytes(StandardCharsets.UTF_8)
      dataOut.writeInt(bytes.length)
      dataOut.write(bytes)
    }

    // Write the task. The task is already serialized, so write it directly to the byte buffer.
    Utils.writeByteBuffer(taskDescription.serializedTask, bytesOut)

    dataOut.close()
    bytesOut.close()
    bytesOut.toByteBuffer
  }

  private def deserializeStringLongMap(dataIn: DataInputStream): HashMap[String, Long] = {
    val map = new HashMap[String, Long]()
    val mapSize = dataIn.readInt()
    for (i <- 0 until mapSize) {
      map(dataIn.readUTF()) = dataIn.readLong()
    }
    map
  }

  def decode(byteBuffer: ByteBuffer): TaskDescription = {
    val dataIn = new DataInputStream(new ByteBufferInputStream(byteBuffer))
    val taskId = dataIn.readLong()
    val attemptNumber = dataIn.readInt()
    val executorId = dataIn.readUTF()
    val name = dataIn.readUTF()
    val index = dataIn.readInt()

    // Read files.
    val taskFiles = deserializeStringLongMap(dataIn)

    // Read jars.
    val taskJars = deserializeStringLongMap(dataIn)

    // Read properties.
    val properties = new Properties()
    val numProperties = dataIn.readInt()
    for (i <- 0 until numProperties) {
      val key = dataIn.readUTF()
      val valueLength = dataIn.readInt()
      val valueBytes = new Array[Byte](valueLength)
      dataIn.readFully(valueBytes)
      properties.setProperty(key, new String(valueBytes, StandardCharsets.UTF_8))
    }

    // Create a sub-buffer for the serialized task into its own buffer (to be deserialized later).
    val serializedTask = byteBuffer.slice()

    new TaskDescription(taskId, attemptNumber, executorId, name, index, taskFiles, taskJars,
      properties, serializedTask)
  }
}