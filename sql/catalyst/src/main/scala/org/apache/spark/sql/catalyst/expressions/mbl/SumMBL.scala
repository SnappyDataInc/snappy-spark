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

package org.apache.spark.sql.catalyst.expressions.mbl

import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.DeclarativeAggregate
import org.apache.spark.sql.types._

case class SumMBL(children: Seq[Expression]) extends DeclarativeAggregate {
  private def numBufferPoints: Integer = 1024 * 3

  override def inputTypes: Seq[DataType] = Seq(ArrayType(IntegerType))

  private def mblPointType: DataType = new StructType()
    .add("ts", IntegerType) // unix timestamp in seconds
    .add("meet", IntegerType)
    .add("beat", IntegerType)
    .add("lose", IntegerType)

  override def dataType: DataType = ArrayType(mblPointType)

  override def nullable: Boolean = false

  private lazy val sumPoints =
    AttributeReference("sum_points", ArrayType(IntegerType), nullable = false)()

  private lazy val numPoints =
    AttributeReference("num_points", IntegerType, nullable = false)()

  private lazy val startTimeInSeconds =
    AttributeReference("start_time_in_seconds", IntegerType, nullable = false)()

  private lazy val stepInSeconds =
    AttributeReference("step_in_seconds", IntegerType, nullable = false)()

  override lazy val aggBufferAttributes: Seq[AttributeReference] =
    sumPoints :: numPoints :: startTimeInSeconds :: stepInSeconds :: Nil

  override lazy val initialValues: Seq[Expression] = Seq(
    {
      val i = UDFUtils.makeIter("sum_mbl_initalValues")
      GenerateArray(Literal(numBufferPoints), i, Literal(0, IntegerType))
    },
    Literal(0),
    Literal(0),
    Literal(0)
  )

  override lazy val updateExpressions: Seq[Expression] = {
    val i = UDFUtils.makeIter("sum_mbl_updateExpressions")
    val arraySize = GetArraySize(children.head)
    Seq(
      DoSeq(
        ForStep(numBufferPoints, 3, i, {
          val weight = GetArrayItem(children.head, i)
          val compValue = GetArrayItem(children.head, i + 1)
          val mtValue = GetArrayItem(children.head, i + 2)

          val meet = compValue === mtValue
          val beat = compValue > mtValue
          val lose = compValue < mtValue

          val meetCount = GetArrayItem(sumPoints, i)
          val beatCount = GetArrayItem(sumPoints, i + 1)
          val loseCount = GetArrayItem(sumPoints, i + 2)

          If (weight > 0,
            If (meet, DoSeq(SetArrayItem(sumPoints, i, meetCount + weight), sumPoints),
              If (beat, DoSeq(SetArrayItem(sumPoints, i + 1, beatCount + weight), sumPoints),
                If (lose, DoSeq(SetArrayItem(sumPoints, i + 2, loseCount + weight), sumPoints),
                  sumPoints)
              )
            ),
            sumPoints)
        }),
        sumPoints),
      arraySize - 2, // numPoints = 数组长度-2
      GetArrayItem(children.head, arraySize - 2), // startTimeInSeconds = 数组倒数第2个元素
      GetArrayItem(children.head, arraySize - 1)  // stepInSeconds = 数组倒数第1个元素
    )
  }

  override lazy val mergeExpressions: Seq[Expression] = Seq(
    {
      val i = UDFUtils.makeIter("sum_mbl_mergeExpressions")
      DoSeq(
        ForStep(numBufferPoints, 3, i, {
          val leftMeet = GetArrayItem(sumPoints.left, i)
          val leftBeat = GetArrayItem(sumPoints.left, i + 1)
          val leftLose = GetArrayItem(sumPoints.left, i + 2)

          val rightMeet = GetArrayItem(sumPoints.right, i)
          val rightBeat = GetArrayItem(sumPoints.right, i + 1)
          val rightLose = GetArrayItem(sumPoints.right, i + 2)

          DoSeq(
            SetArrayItem(sumPoints, i, leftMeet + rightMeet),
            SetArrayItem(sumPoints, i + 1, leftBeat + rightBeat),
            SetArrayItem(sumPoints, i + 2, leftLose + rightLose)
          )
        }),
        sumPoints)
    },

    If(numPoints.left === 0, numPoints.right, numPoints.left), // num_points
    If(startTimeInSeconds.left === 0,
      startTimeInSeconds.right, startTimeInSeconds.left), // start_time_in_seconds
    If(stepInSeconds.left === 0, stepInSeconds.right, stepInSeconds.left) // step_in_seconds
  )

  override lazy val evaluateExpression: Expression = {
    val i = UDFUtils.makeIter("sum_mbl_evaluateExpression")

    val meet = GetArrayItem(sumPoints, i * 3)
    val beat = GetArrayItem(sumPoints, i * 3 + 1)
    val lose = GetArrayItem(sumPoints, i * 3 + 2)

    GenerateArray(numPoints / 3, i,
      CreateLocalStruct(Seq(
        startTimeInSeconds + (i + 1) * stepInSeconds,
        meet,
        beat,
        lose)
      )
    )
  }
}
