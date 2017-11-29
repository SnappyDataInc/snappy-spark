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
import org.apache.spark.sql.types.{IntegerType, _}

case class RoomStatusMBL(children: Seq[Expression]) extends DeclarativeAggregate {

  private lazy val startTimeInSeconds: Int = children.head.eval().asInstanceOf[Int]
  private lazy val endTimeInSeconds: Int = children(1).eval().asInstanceOf[Int]
  private lazy val stepInSeconds: Int = children(2).eval().asInstanceOf[Int]
  private lazy val durationInSeconds: Int = children(3).eval().asInstanceOf[Int]

  private lazy val numRowsReal: Int = (endTimeInSeconds - startTimeInSeconds) / stepInSeconds

  private lazy val numRows: Int = if (numRowsReal > 1024) 1024 else numRowsReal

  private lazy val numPoints: Int = numRows * 3

  private lazy val arraySize = numPoints + 2

  private lazy val pointSpan: Int =
    if (durationInSeconds <= stepInSeconds) 1 else durationInSeconds / stepInSeconds

  override def inputTypes: Seq[DataType] = Seq(
    IntegerType,  // 1: startTimeInSeconds
    IntegerType,  // 2: endTimeInSeconds
    IntegerType,  // 3: stepInSeconds
    IntegerType,  // 4: durationInSeconds
    IntegerType,  // 5: crawTime
    IntegerType,  // 6: compRoomStatus
    IntegerType,  // 7: mtRoomStatus
    IntegerType   // 8: weight
  )

  override def dataType: DataType = ArrayType(IntegerType)

  override def nullable: Boolean = false

  private lazy val points = AttributeReference("points", ArrayType(IntegerType), nullable = false)()

  override lazy val aggBufferAttributes: Seq[AttributeReference] = points :: Nil

  override lazy val initialValues: Seq[Expression] = Seq({
    val i = UDFUtils.makeIter("mbl_rs_initalValues")
    GenerateArray(Literal(arraySize), i, Literal(-1, IntegerType))
  })

  override lazy val updateExpressions: Seq[Expression] = {
    val i = UDFUtils.makeIter("mbl_rs_updateExpressions")

    val crawlTime = children(4)
    val compRoomStatus = children(5)
    val mtRoomStatus = children(6)

    Seq(
      DoSeq(
        ForStep(pointSpan, 1, i, {
          val pointIndex0 = ((crawlTime - startTimeInSeconds) / stepInSeconds + i) * 3
          val pointIndex1 = pointIndex0 + 1
          val pointIndex2 = pointIndex0 + 2

          val prevWeight = GetArrayItemWithSize(arraySize, points, pointIndex0)
          val prevCompRoomStatus = GetArrayItemWithSize(arraySize, points, pointIndex1)
          val prevMtRoomStatus = GetArrayItemWithSize(arraySize, points, pointIndex2)

          val weight = children(7)

          If(pointIndex0 < numPoints &&
            crawlTime > startTimeInSeconds && crawlTime < endTimeInSeconds,
            Then(
              If(prevWeight < 0,
                SetArrayItem(points, pointIndex0, weight),
                /* ignore */ Literal(true)),
              If(compRoomStatus > prevCompRoomStatus,
                SetArrayItem(points, pointIndex1, If(compRoomStatus >= 1, 1, 0)),
                /* ignore */ Literal(true)),
              If(mtRoomStatus > prevMtRoomStatus,
                SetArrayItem(points, pointIndex2, If(mtRoomStatus >= 1, 1, 0)),
                /* ignore */ Literal(true)),
              points),
            Else(
              points
            ))
        }),
        points)
    )
  }

  override lazy val mergeExpressions: Seq[Expression] = {
    val i = UDFUtils.makeIter("mbl_rs_mergeExpressions")
    Seq(
      DoSeq(
        ForStep(numPoints, 3, i, {
          val leftWeight = GetArrayItemWithSize(arraySize, points.left, i)
          val leftCompRoomStatus = GetArrayItemWithSize(arraySize, points.left, i + 1)
          val leftMtRoomStatus = GetArrayItemWithSize(arraySize, points.left, i + 2)

          val rightWeight = GetArrayItemWithSize(arraySize, points.right, i)
          val rightCompRoomStatus = GetArrayItemWithSize(arraySize, points.right, i + 1)
          val rightMtRoomStatus = GetArrayItemWithSize(arraySize, points.right, i + 2)

          If(leftWeight > 0,
            SetArrayItem(points, i, leftWeight),
            SetArrayItem(points, i, rightWeight))
          If(leftCompRoomStatus > rightCompRoomStatus,
            SetArrayItem(points, i + 1, leftCompRoomStatus),
            SetArrayItem(points, i + 1, rightCompRoomStatus))
          If(leftMtRoomStatus > rightMtRoomStatus,
            SetArrayItem(points, i + 2, leftMtRoomStatus),
            SetArrayItem(points, i + 2, rightMtRoomStatus))
        }),
        points)
    )
  }

  override lazy val evaluateExpression: Expression = {
    DoSeq(
      // 把startTime和step添加到数据最后两个元素中，为sum时候使用
      SetArrayItem(points, Literal(numPoints), Literal(startTimeInSeconds)),
      SetArrayItem(points, Literal(numPoints + 1), Literal(stepInSeconds)),
      points
    )
  }
}