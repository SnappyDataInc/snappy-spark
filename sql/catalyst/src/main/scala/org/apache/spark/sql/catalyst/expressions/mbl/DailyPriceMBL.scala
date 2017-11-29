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

case class DailyPriceMBL(children: Seq[Expression]) extends DeclarativeAggregate {

  private lazy val stepInSeconds: Int = children.head.eval().asInstanceOf[Int]

  private lazy val durationInSeconds: Int = children(1).eval().asInstanceOf[Int]

  private lazy val numRows: Int = 86400 / stepInSeconds

  private lazy val numPoints: Int = numRows * 3

  private lazy val arraySize = numPoints + 2

  private lazy val pointSpan: Int =
    if (durationInSeconds <= stepInSeconds) 1 else durationInSeconds / stepInSeconds

  override def inputTypes: Seq[DataType] = Seq(
    IntegerType,  // 1: stepInSeconds
    IntegerType,  // 2: durationInSeconds
    IntegerType,  // 3: crawTime
    IntegerType,  // 4: compPrice
    IntegerType,  // 5: mtPrice
    IntegerType   // 6: weight
  )

  override def dataType: DataType = ArrayType(IntegerType)

  override def nullable: Boolean = false

  private lazy val points = AttributeReference("points", ArrayType(IntegerType), nullable = false)()

  private lazy val dayStartTime =
    AttributeReference("day_start_time", IntegerType, nullable = false)()

  override lazy val aggBufferAttributes: Seq[AttributeReference] = points :: dayStartTime :: Nil

  override lazy val initialValues: Seq[Expression] = Seq(
    GenerateArray(Literal(arraySize),
      UDFUtils.makeIter("mbl_initalValues"), Literal(-1, IntegerType)),
    Literal(-1, IntegerType)
  )

  override lazy val updateExpressions: Seq[Expression] = {
    val i = UDFUtils.makeIter("mbl_updateExpressions")

    val crawlTime = children(2)
    val compPrice = children(3)
    val mtPrice = children(4)

    Seq(
      DoSeq(
        ForStep(pointSpan, 1, i, {
          val pointIndex0 = (((crawlTime + 28800) % 86400) / stepInSeconds + i) * 3
          val pointIndex1 = pointIndex0 + 1
          val pointIndex2 = pointIndex0 + 2

          val prevWeight = GetArrayItemWithSize(arraySize, points, pointIndex0)
          val prevCompPrice = GetArrayItemWithSize(arraySize, points, pointIndex1)
          val prevMtPrice = GetArrayItemWithSize(arraySize, points, pointIndex2)

          val weight = children(5)

          If(pointIndex0 < numPoints &&
            (prevWeight < 0 || compPrice < prevCompPrice ||
              compPrice === prevCompPrice && mtPrice < prevMtPrice),
            Then(
              SetArrayItem(points, pointIndex0, weight),
              SetArrayItem(points, pointIndex1, compPrice),
              SetArrayItem(points, pointIndex2, mtPrice),
              points),
            Else(
              points
            ))
        }),
        points),
      If(dayStartTime < 0, crawlTime - (crawlTime + 28800) % 86400, dayStartTime)
    )
  }

  override lazy val mergeExpressions: Seq[Expression] = {
    val i = UDFUtils.makeIter("mbl_mergeExpressions")
    Seq(
      DoSeq(
        ForStep(numPoints, 3, i, {
          val leftWeight = GetArrayItemWithSize(arraySize, points.left, i)
          val leftCompPrice = GetArrayItemWithSize(arraySize, points.left, i + 1)
          val leftMtPrice = GetArrayItemWithSize(arraySize, points.left, i + 2)

          val rightWeight = GetArrayItemWithSize(arraySize, points.right, i)
          val rightCompPrice = GetArrayItemWithSize(arraySize, points.right, i + 1)
          val rightMtPrice = GetArrayItemWithSize(arraySize, points.right, i + 2)

          If(leftCompPrice < rightCompPrice ||
            (leftCompPrice === rightCompPrice && leftMtPrice < rightMtPrice),
            Then(
              SetArrayItem(points, i, leftWeight),
              SetArrayItem(points, i + 1, leftCompPrice),
              SetArrayItem(points, i + 2, leftMtPrice)),
            Else(
              SetArrayItem(points, i, rightWeight),
              SetArrayItem(points, i + 1, rightCompPrice),
              SetArrayItem(points, i + 2, rightMtPrice))
          )
        }),
        points),
      dayStartTime
    )
  }

  override lazy val evaluateExpression: Expression = {
    DoSeq(
      // 把startTime和step添加到数据最后两个元素中，为sum时候使用
      SetArrayItem(points, Literal(numPoints), dayStartTime),
      SetArrayItem(points, Literal(numPoints + 1), Literal(stepInSeconds)),
      points
    )
  }
}