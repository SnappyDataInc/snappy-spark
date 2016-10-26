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

import java.io._
import java.nio.ByteBuffer
import java.util.Properties

import com.esotericsoftware.kryo.{Kryo, KryoSerializable}
import com.esotericsoftware.kryo.io.{Input, Output}

import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.rdd.RDD

/**
 * A task that sends back the output to the driver application.
 *
 * See [[Task]] for more information.
 *
 * @param stageId id of the stage this task belongs to
 * @param stageAttemptId attempt id of the stage this task belongs to
 * @param _taskBinary broadcasted version of the serialized RDD and the function to apply on each
 *                   partition of the given RDD. Once deserialized, the type should be
 *                   (RDD[T], (TaskContext, Iterator[T]) => U).
 * @param partition partition of the RDD this task is associated with
 * @param locs preferred task execution locations for locality scheduling
 * @param _outputId index of the task in this job (a job can launch tasks on only a subset of the
 *                 input RDD's partitions).
 * @param localProperties copy of thread-local properties set by the user on the driver side.
 * @param metrics a [[TaskMetrics]] that is created at driver side and sent to executor side.
 */
private[spark] class ResultTask[T, U](
    stageId: Int,
    stageAttemptId: Int,
    _taskBinaryBytes: Option[Array[Byte]],
    _taskBinary: Option[Broadcast[Array[Byte]]],
    private var partition: Partition,
    locs: Seq[TaskLocation],
    private var _outputId: Int,
    localProperties: Properties,
    metrics: TaskMetrics)
  extends Task[U](stageId, stageAttemptId, partition.index,
    _taskBinaryBytes, _taskBinary, metrics, localProperties)
  with Serializable with KryoSerializable {

  final def outputId: Int = _outputId

  @transient private[this] val preferredLocs: Seq[TaskLocation] = {
    if (locs == null) Nil else locs.toSet.toSeq
  }

  override def runTask(context: TaskContext): U = {
    // Deserialize the RDD and the func using the broadcast variables.
    val deserializeStartTime = System.nanoTime()
    val ser = SparkEnv.get.closureSerializer.newInstance()
    val taskBytes = taskBinaryBytes.getOrElse(taskBinary.get.value)
    val (rdd, func) = ser.deserialize[(RDD[T], (TaskContext, Iterator[T]) => U)](
      ByteBuffer.wrap(taskBytes), Thread.currentThread.getContextClassLoader)
    _executorDeserializeTime = math.max(System.nanoTime() - deserializeStartTime, 0L)

    func(context, rdd.iterator(partition, context))
  }

  // This is only callable on the driver side.
  override def preferredLocations: Seq[TaskLocation] = preferredLocs

  override def toString: String = "ResultTask(" + stageId + ", " + partitionId + ")"

  override def write(kryo: Kryo, output: Output): Unit = {
    super.writeKryo(kryo, output)
    kryo.writeClassAndObject(output, partition)
    output.writeInt(_outputId)
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    super.readKryo(kryo, input)
    partition = kryo.readClassAndObject(input).asInstanceOf[Partition]
    _outputId = input.readInt()
  }
}
