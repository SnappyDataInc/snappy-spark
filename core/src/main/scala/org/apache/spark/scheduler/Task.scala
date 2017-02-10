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
import java.util.Properties

import scala.collection.mutable
import scala.collection.mutable.HashMap

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.{Input, Output}

import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.memory.{MemoryMode, TaskMemoryManager}
import org.apache.spark.metrics.MetricsSystem
import org.apache.spark.serializer.SerializerInstance
import org.apache.spark.util._

/**
 * A unit of execution. We have two kinds of Task's in Spark:
 *
 *  - [[org.apache.spark.scheduler.ShuffleMapTask]]
 *  - [[org.apache.spark.scheduler.ResultTask]]
 *
 * A Spark job consists of one or more stages. The very last stage in a job consists of multiple
 * ResultTasks, while earlier stages consist of ShuffleMapTasks. A ResultTask executes the task
 * and sends the task output back to the driver application. A ShuffleMapTask executes the task
 * and divides the task output to multiple buckets (based on the task's partitioner).
 *
 * @param _stageId id of the stage this task belongs to
 * @param _stageAttemptId attempt id of the stage this task belongs to
 * @param _partitionId index of the number in the RDD
 * @param _metrics a [[TaskMetrics]] that is created at driver side and sent to executor side.
 * @param localProperties copy of thread-local properties set by the user on the driver side.
 *
 * The parameters below are optional:
 * @param jobId id of the job this task belongs to
 * @param appId id of the app this task belongs to
 * @param appAttemptId attempt id of the app this task belongs to
 */
private[spark] abstract class Task[T](
    private var _stageId: Int,
    private var _stageAttemptId: Int,
    private var _partitionId: Int,
    @transient private[spark] var taskData: TaskData = TaskData.EMPTY,
    // The default value is only used in tests.
    protected var taskBinary: Option[Broadcast[Array[Byte]]] = None,
    private var _metrics: TaskMetrics = TaskMetrics.registered,
    @transient var localProperties: Properties = new Properties,
    val jobId: Option[Int] = None,
    val appId: Option[String] = None,
    val appAttemptId: Option[String] = None) extends Serializable {

  final def stageId: Int = _stageId

  final def stageAttemptId: Int = _stageAttemptId

  final def partitionId: Int = _partitionId

  final def metrics: TaskMetrics = _metrics

  @transient private[spark] var taskDataBytes: Array[Byte] = _

  protected final def getTaskBytes: Array[Byte] = {
    val bytes = taskDataBytes
    if ((bytes ne null) && bytes.length > 0) bytes else taskBinary.get.value
  }

  /**
   * Called by [[org.apache.spark.executor.Executor]] to run this task.
   *
   * @param taskAttemptId an identifier for this task attempt that is unique within a SparkContext.
   * @param attemptNumber how many times this task has been attempted (0 for the first attempt)
   * @return the result of the task along with updates of Accumulators.
   */
  final def run(
      taskAttemptId: Long,
      attemptNumber: Int,
      metricsSystem: MetricsSystem): T = {
    SparkEnv.get.blockManager.registerTask(taskAttemptId)
    context = new TaskContextImpl(
      stageId,
      partitionId,
      taskAttemptId,
      attemptNumber,
      taskMemoryManager,
      localProperties,
      metricsSystem,
      metrics)
    TaskContext.setTaskContext(context)
    taskThread = Thread.currentThread()

    if (_killed) {
      kill(interruptThread = false)
    }

    new CallerContext("TASK", appId, appAttemptId, jobId, Option(stageId), Option(stageAttemptId),
      Option(taskAttemptId), Option(attemptNumber)).setCurrentContext()

    try {
      runTask(context)
    } catch {
      case e: Throwable =>
        // Catch all errors; run task failure callbacks, and rethrow the exception.
        try {
          context.markTaskFailed(e)
        } catch {
          case t: Throwable =>
            e.addSuppressed(t)
        }
        throw e
    } finally {
      // Call the task completion callbacks.
      context.markTaskCompleted()
      try {
        Utils.tryLogNonFatalError {
          // Release memory used by this thread for unrolling blocks
          SparkEnv.get.blockManager.memoryStore.releaseUnrollMemoryForThisTask(MemoryMode.ON_HEAP)
          SparkEnv.get.blockManager.memoryStore.releaseUnrollMemoryForThisTask(MemoryMode.OFF_HEAP)
          // Notify any tasks waiting for execution memory to be freed to wake up and try to
          // acquire memory again. This makes impossible the scenario where a task sleeps forever
          // because there are no other tasks left to notify it. Since this is safe to do but may
          // not be strictly necessary, we should revisit whether we can remove this in the future.
          val memoryManager = SparkEnv.get.memoryManager
          memoryManager.synchronized { memoryManager.notifyAll() }
        }
      } finally {
        TaskContext.unset()
      }
    }
  }

  @transient private var taskMemoryManager: TaskMemoryManager = _

  def setTaskMemoryManager(taskMemoryManager: TaskMemoryManager): Unit = {
    this.taskMemoryManager = taskMemoryManager
  }

  def runTask(context: TaskContext): T

  def preferredLocations: Seq[TaskLocation] = Nil

  // Map output tracker epoch. Will be set by TaskScheduler.
  var epoch: Long = -1

  // Task context, to be initialized in run().
  @transient protected var context: TaskContextImpl = _

  // The actual Thread on which the task is running, if any. Initialized in run().
  @volatile @transient private var taskThread: Thread = _

  // A flag to indicate whether the task is killed. This is used in case context is not yet
  // initialized when kill() is invoked.
  @volatile @transient private var _killed = false

  protected var _executorDeserializeTime: Long = 0
  protected var _executorDeserializeCpuTime: Long = 0

  /**
   * Whether the task has been killed.
   */
  def killed: Boolean = _killed

  /**
   * Returns the amount of time spent deserializing the RDD and function to be run in nanos.
   */
  def executorDeserializeTime: Long = _executorDeserializeTime
  def executorDeserializeCpuTime: Long = _executorDeserializeCpuTime

  /**
   * Collect the latest values of accumulators used in this task. If the task failed,
   * filter out the accumulators whose values should not be included on failures.
   */
  def collectAccumulatorUpdates(taskFailed: Boolean = false): Seq[AccumulatorV2[_, _]] = {
    if (context != null) {
      context.taskMetrics.internalAccums.filter { a =>
        // RESULT_SIZE accumulator is always zero at executor, we need to send it back as its
        // value will be updated at driver side.
        // Note: internal accumulators representing task metrics always count failed values
        !a.isZero || a.name == Some(InternalAccumulator.RESULT_SIZE)
      // zero value external accumulators may still be useful, e.g. SQLMetrics, we should not filter
      // them out.
      } ++ context.taskMetrics.externalAccums.filter(a => !taskFailed || a.countFailedValues)
    } else {
      Seq.empty
    }
  }

  /**
   * Kills a task by setting the interrupted flag to true. This relies on the upper level Spark
   * code and user code to properly handle the flag. This function should be idempotent so it can
   * be called multiple times.
   * If interruptThread is true, we will also call Thread.interrupt() on the Task's executor thread.
   */
  def kill(interruptThread: Boolean) {
    _killed = true
    if (context != null) {
      context.markInterrupted()
    }
    if (interruptThread && taskThread != null) {
      taskThread.interrupt()
    }
  }

  protected def writeKryo(kryo: Kryo, output: Output): Unit = {
    output.writeInt(_stageId)
    output.writeVarInt(_stageAttemptId, true)
    output.writeVarInt(_partitionId, true)
    output.writeLong(epoch)
    output.writeLong(_executorDeserializeTime)
    if ((taskData ne null) && taskData.uncompressedLen > 0) {
      // actual bytes will be shipped in TaskDescription
      output.writeBoolean(true)
    } else {
      output.writeBoolean(false)
      kryo.writeClassAndObject(output, taskBinary.get)
    }
    _metrics.write(kryo, output)
  }

  def readKryo(kryo: Kryo, input: Input): Unit = {
    _stageId = input.readInt()
    _stageAttemptId = input.readVarInt(true)
    _partitionId = input.readVarInt(true)
    epoch = input.readLong()
    _executorDeserializeTime = input.readLong()
    // actual bytes are shipped in TaskDescription
    taskData = TaskData.EMPTY
    if (input.readBoolean()) {
      taskBinary = None
    } else {
      taskBinary = Some(kryo.readClassAndObject(input)
          .asInstanceOf[Broadcast[Array[Byte]]])
    }
    _metrics = new TaskMetrics
    _metrics.read(kryo, input)
  }
}

/**
 * Handles transmission of tasks and their dependencies, because this can be slightly tricky. We
 * need to send the list of JARs and files added to the SparkContext with each task to ensure that
 * worker nodes find out about it, but we can't make it part of the Task because the user's code in
 * the task might depend on one of the JARs. Thus we serialize each task as multiple objects, by
 * first writing out its dependencies.
 */
private[spark] object Task {
  /**
   * Serialize a task and the current app dependencies (files and JARs added to the SparkContext)
   */
  def serializeWithDependencies(
      task: Task[_],
      currentFiles: mutable.Map[String, Long],
      currentJars: mutable.Map[String, Long],
      serializer: SerializerInstance)
    : ByteBuffer = {

    val out = new ByteBufferOutputStream(4096)
    val dataOut = new DataOutputStream(out)

    // Write currentFiles
    dataOut.writeInt(currentFiles.size)
    for ((name, timestamp) <- currentFiles) {
      dataOut.writeUTF(name)
      dataOut.writeLong(timestamp)
    }

    // Write currentJars
    dataOut.writeInt(currentJars.size)
    for ((name, timestamp) <- currentJars) {
      dataOut.writeUTF(name)
      dataOut.writeLong(timestamp)
    }

    // Write the task properties separately so it is available before full task deserialization.
    val props = task.localProperties
    val numProps = props.size()
    dataOut.writeInt(numProps)
    if (numProps > 0) {
      val keys = props.keys()
      while (keys.hasMoreElements) {
        val key = keys.nextElement().asInstanceOf[String]
        dataOut.writeUTF(key)
        dataOut.writeUTF(props.getProperty(key))
      }
    }

    // Write the task itself and finish
    dataOut.flush()
    val taskBytes = serializer.serialize(task)
    Utils.writeByteBuffer(taskBytes, out)
    out.close()
    out.toByteBuffer
  }

  /**
   * Deserialize the list of dependencies in a task serialized with serializeWithDependencies,
   * and return the task itself as a serialized ByteBuffer. The caller can then update its
   * ClassLoaders and deserialize the task.
   *
   * @return (taskFiles, taskJars, taskProps, taskBytes)
   */
  def deserializeWithDependencies(serializedTask: ByteBuffer)
    : (HashMap[String, Long], HashMap[String, Long], Properties, ByteBuffer) = {

    val in = new ByteBufferInputStream(serializedTask)
    val dataIn = new DataInputStream(in)

    // Read task's files
    val taskFiles = new HashMap[String, Long]()
    val numFiles = dataIn.readInt()
    for (i <- 0 until numFiles) {
      taskFiles(dataIn.readUTF()) = dataIn.readLong()
    }

    // Read task's JARs
    val taskJars = new HashMap[String, Long]()
    val numJars = dataIn.readInt()
    for (i <- 0 until numJars) {
      taskJars(dataIn.readUTF()) = dataIn.readLong()
    }

    val taskProps = new Properties
    var numProps = dataIn.readInt()
    while (numProps > 0) {
      val key = dataIn.readUTF()
      taskProps.setProperty(key, dataIn.readUTF())
      numProps -= 1
    }

    // Create a sub-buffer for the rest of the data, which is the serialized Task object
    val subBuffer = serializedTask.slice()  // ByteBufferInputStream will have read just up to task
    (taskFiles, taskJars, taskProps, subBuffer)
  }
}

private[spark] final class TaskData private(var compressedBytes: Array[Byte],
    var uncompressedLen: Int, var reference: Int) extends Serializable {

  def this(compressedBytes: Array[Byte], uncompressedLen: Int) =
    this(compressedBytes, uncompressedLen, TaskData.NO_REF)

  @transient private var decompressed: Array[Byte] = _

  /** decompress the common task data if present */
  def decompress(env: SparkEnv = SparkEnv.get): Array[Byte] = {
    if (uncompressedLen > 0) {
      if (decompressed eq null) {
        decompressed = env.createCompressionCodec.decompress(compressedBytes,
          0, compressedBytes.length, uncompressedLen)
      }
      decompressed
    } else TaskData.EMPTY_BYTES
  }

  override def hashCode(): Int = java.util.Arrays.hashCode(compressedBytes)

  override def equals(obj: Any): Boolean = obj match {
    case d: TaskData =>
      uncompressedLen == d.uncompressedLen &&
          reference == d.reference &&
          java.util.Arrays.equals(compressedBytes, d.compressedBytes)
    case _ => false
  }
}

private[spark] object TaskData {

  private val NO_REF: Int = -1
  private val EMPTY_BYTES: Array[Byte] = Array.empty[Byte]
  private val FIRST: TaskData = new TaskData(EMPTY_BYTES, 0, 0)
  val EMPTY: TaskData = new TaskData(EMPTY_BYTES, 0, -2)

  def apply(reference: Int): TaskData = {
    if (reference == 0) FIRST
    else if (reference > 0) new TaskData(EMPTY_BYTES, 0, reference)
    else EMPTY
  }

  def write(data: TaskData, output: Output): Unit = Utils.tryOrIOException {
    if (data.reference != NO_REF) {
      output.writeVarInt(data.reference, false)
    } else {
      val bytes = data.compressedBytes
      assert(bytes != null)
      output.writeVarInt(NO_REF, false)
      output.writeVarInt(data.uncompressedLen, true)
      output.writeVarInt(bytes.length, true)
      output.writeBytes(bytes)
    }
  }

  def read(input: Input): TaskData = Utils.tryOrIOException {
    val reference = input.readVarInt(false)
    if (reference != NO_REF) {
      TaskData(reference)
    } else {
      val uncompressedLen = input.readVarInt(true)
      val bytesLen = input.readVarInt(true)
      new TaskData(input.readBytes(bytesLen), uncompressedLen)
    }
  }
}
