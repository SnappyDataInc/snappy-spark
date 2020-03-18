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
 * Copyright (c) 2017-2020 TIBCO Software Inc. All rights reserved.
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

package org.apache.spark.broadcast

import java.io.{InputStream, ObjectInput, ObjectInputStream, ObjectOutput, ObjectOutputStream, ObjectStreamClass}
import java.nio.ByteBuffer

import scala.reflect.ClassTag

import com.esotericsoftware.kryo.{Kryo, KryoSerializable}
import com.esotericsoftware.kryo.io.{ByteBufferOutput, Input, Output}
import net.jpountz.lz4.LZ4Factory

import org.apache.spark.SparkEnv
import org.apache.spark.serializer.{KryoInputObjectInputBridge, KryoOutputObjectOutputBridge}

private[spark] class DirectBroadcast[T: ClassTag](@transient private var obj: T)
    extends Broadcast[T](0L) with KryoSerializable with Serializable {

  /**
   * Returns true if compression is enabled. LZ4 is used for fastest compression/decompression.
   */
  private def doCompress(): Boolean =
    SparkEnv.get.conf.getBoolean("spark.broadcast.compress", defaultValue = true)

  override protected def getValue(): T = obj

  override protected def doUnpersist(blocking: Boolean): Unit = {}

  override protected def doDestroy(blocking: Boolean): Unit = {}

  override def toString: String = "DirectBroadcast(" + id + ")"

  /**
   * Compresses the object in this DirectBroadcast using LZ4 and returns the compressed buffer.
   */
  private def writeCompressed(out: ObjectOutput, kryo: Kryo): Unit = {
    var newOutput: ByteBufferOutput = null
    val decompressedBuffer = obj match {
      case bytes: Array[Byte] => ByteBuffer.wrap(bytes)
      case _ =>
        newOutput = new ByteBufferOutput(4096, -1)
        if (kryo ne null) kryo.writeClassAndObject(newOutput, obj)
        else {
          val objOut = new ObjectOutputStream(newOutput)
          objOut.writeObject(obj)
          objOut.flush()
        }
        val buffer = newOutput.getByteBuffer
        buffer.flip()
        buffer
    }

    val decompressedLength = decompressedBuffer.limit()
    val compressor = LZ4Factory.fastestInstance().fastCompressor()
    val compressedBytes = new Array[Byte](compressor.maxCompressedLength(decompressedLength))
    val compressedBuffer = ByteBuffer.wrap(compressedBytes)
    val compressedLength = compressor.compress(decompressedBuffer, 0, decompressedLength,
      compressedBuffer, 0, compressedBuffer.limit())

    if (newOutput eq null) {
      out.writeBoolean(true) // obj is Array[Byte]
    } else {
      out.writeBoolean(false) // obj is not Array[Byte]
      newOutput.release()
    }
    out.writeInt(decompressedLength)
    out.writeInt(compressedLength)
    out.write(compressedBuffer.array(), 0, compressedLength)
  }

  /**
   * Decompress and store the object compressed using [[writeCompressed]].
   */
  private def readCompressed(in: ObjectInput, kryo: Kryo): Unit = {
    val isByteArray = in.readBoolean()
    val decompressedLength = in.readInt()
    val compressedLength = in.readInt()
    val compressedBytes = new Array[Byte](compressedLength)
    in.readFully(compressedBytes, 0, compressedLength)

    val decompressedBytes = LZ4Factory.fastestInstance().fastDecompressor().decompress(
      compressedBytes, decompressedLength)
    if (isByteArray) obj = decompressedBytes.asInstanceOf[T]
    else {
      val in = new Input(decompressedBytes)
      if (kryo ne null) obj = kryo.readClassAndObject(in).asInstanceOf[T]
      else obj = new SparkObjectInputStream(in).readObject().asInstanceOf[T]
    }
  }

  // noinspection ScalaUnusedSymbol
  private def writeObject(out: ObjectOutputStream): Unit = {
    val compress = doCompress()
    out.writeBoolean(compress)
    if (compress) writeCompressed(out, kryo = null)
    else out.writeObject(obj)
  }

  // noinspection ScalaUnusedSymbol
  private def readObject(in: ObjectInputStream): Unit = {
    if (in.readBoolean()) readCompressed(in, kryo = null)
    else obj = in.readObject().asInstanceOf[T]
    _isValid = true
  }

  override def write(kryo: Kryo, out: Output): Unit = {
    val compress = doCompress()
    out.writeBoolean(compress)
    if (compress) writeCompressed(new KryoOutputObjectOutputBridge(kryo, out), kryo)
    else kryo.writeClassAndObject(out, obj)
  }

  override def read(kryo: Kryo, in: Input): Unit = {
    if (in.readBoolean()) readCompressed(new KryoInputObjectInputBridge(kryo, in), kryo)
    else obj = kryo.readClassAndObject(in).asInstanceOf[T]
    _isValid = true
  }
}

/**
 * ObjectInputStream extension to honor Thread's context ClassLoader.
 *
 * This is different from Utils.deserialize in that it correctly loads primitive classes
 * and any other failures by delegating to base ObjectInputStream.resolveClass
 */
private[spark] class SparkObjectInputStream(in: InputStream) extends ObjectInputStream(in) {
  override def resolveClass(desc: ObjectStreamClass): Class[_] = {
    try {
      // scalastyle:off classforname
      Class.forName(desc.getName, false, Thread.currentThread.getContextClassLoader)
      // scalastyle:on classforname
    } catch {
      case _: ClassNotFoundException => super.resolveClass(desc)
    }
  }
}
