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

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult
import org.apache.spark.sql.catalyst.expressions.{Expression, _}
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.util.{ArrayData, GenericArrayData, TypeUtils}
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.Platform
import org.apache.spark.unsafe.array.ByteArrayMethods

case class IterRef(iterName: String) extends Expression with NonSQLExpression {

  // deterministic=false保证不会被优化器将使用了iter的逻辑抽取到for循环之外
  override def deterministic: Boolean = false

  override def nullable: Boolean = false

  override def eval(input: InternalRow): Any =
    new RuntimeException("eval() not implemented, only codegen is allowed.")

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    ev.copy(code = s"""
         |final boolean ${ev.isNull} = false;
         |final int ${ev.value} = this.$iterName;
      """.stripMargin)
  }

  override def dataType: DataType = IntegerType

  override def children: Seq[Expression] = Nil
}

case class ForStep(size: Int, step: Int, iter: IterRef, child: Expression)
  extends Expression with NonSQLExpression {

  override def children: Seq[Expression] = child :: Nil

  override def foldable: Boolean = false

  override def checkInputDataTypes(): TypeCheckResult = TypeCheckResult.TypeCheckSuccess

  override def dataType: DataType = BooleanType

  override def nullable: Boolean = false

  override def deterministic: Boolean = false

  val iterName: String = iter.iterName

  override def eval(input: InternalRow): Any = true

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val values = ctx.freshName("values")
    ctx.addMutableState("int", iterName, s"this.$iterName = 0;")

    ev.copy(code = s"""
      for (this.$iterName = 0; this.$iterName < $size; this.$iterName += $step) {
      """ + child.genCode(ctx).code +  s"""
      }
      final boolean ${ev.isNull} = false;
      final boolean ${ev.value} = true; /* for code end here */
      """, isNull = "false")
  }

  override def prettyName: String = "for_step"
}

case class GenerateArray(size: Expression, iter: IterRef, child: Expression) extends Expression {

  override def children: Seq[Expression] = size :: child :: Nil

  override def checkInputDataTypes(): TypeCheckResult =
    TypeUtils.checkForSameTypeInputExpr(children.map(_.dataType), "function array")

  override def dataType: DataType = {
    ArrayType(
      children.tail.headOption.map(_.dataType).getOrElse(NullType),
      containsNull = children.tail.exists(_.nullable))
  }

  override def nullable: Boolean = false

  override def deterministic: Boolean = false

  val iterName: String = iter.iterName

  override def eval(input: InternalRow): Any = {
    new GenericArrayData(children.map(_.eval(input)).toArray)
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val arrayClass = classOf[GenericArrayData].getName
    val values = ctx.freshName("values")
    val sizeName = ctx.freshName("array_size")
    ctx.addMutableState("Object[]", values, s"this.$values = null;")
    ctx.addMutableState("int", iterName, s"this.$iterName = 0;")

    val evalSize = size.genCode(ctx)
    ev.copy(code = s"""
      ${evalSize.code}
      int $sizeName = ${evalSize.value};
      this.$values = new Object[$sizeName];
      for (this.$iterName = 0; this.$iterName < $sizeName; this.$iterName++) {""" + {
        val eval = child.genCode(ctx)
        eval.code + s"""
          if (${eval.isNull}) {
            int n = this.$iterName;
            $values[n] = null;
          } else {
            int n = this.$iterName;
            $values[n] = ${eval.value};
          }
        }"""
      } +
      s"""
        final ArrayData ${ev.value} = new $arrayClass($values);
        this.$values = null;
      """, isNull = "false")
  }

  override def prettyName: String = "array"
}

case class GenerateUnsafeArray(size: Expression, iter: IterRef, child: Expression)
  extends Expression {

  override def children: Seq[Expression] = size :: child :: Nil

  override def checkInputDataTypes(): TypeCheckResult =
    TypeUtils.checkForSameTypeInputExpr(children.map(_.dataType), "function array")

  override def dataType: DataType = {
    ArrayType(
      children.tail.headOption.map(_.dataType).getOrElse(NullType),
      containsNull = children.tail.exists(_.nullable))
  }

  override def nullable: Boolean = false

  override def deterministic: Boolean = false

  private val iterName: String = iter.iterName
  private val elementType = child.dataType

  override def eval(input: InternalRow): Any = {
    new GenericArrayData(children.map(_.eval(input)).toArray)
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val (preprocess, assigns, postprocess, arrayData) =
      GenArrayData.genCodeToCreateArrayData(ctx, elementType,
        size.eval().asInstanceOf[Int], iterName, child)
    ev.copy(
      code = preprocess + assigns + postprocess,
      value = arrayData,
      isNull = "false")
  }

  override def prettyName: String = "unsafe_array"
}

private object GenArrayData {
  def genCodeToCreateArrayData(
                                ctx: CodegenContext,
                                elementType: DataType,
                                size: Int,
                                iterName: String,
                                child: Expression): (String, String, String, String) = {
    val arrayName = ctx.freshName("array")
    val arrayDataName = ctx.freshName("arrayData")

    val unsafeArraySizeInBytes =
      UnsafeArrayData.calculateHeaderPortionInBytes(size +
        ByteArrayMethods.roundNumberOfBytesToNearestWord(elementType.defaultSize * size))
    val baseOffset = Platform.BYTE_ARRAY_OFFSET

    val primitiveValueTypeName = ctx.primitiveTypeName(elementType)
    val assignments =
      s"""
      for (int $iterName = 0; $iterName < $size; $iterName++) {""" + {
        val eval = child.genCode(ctx)
        eval.code + s"""
        if (${eval.isNull}) {
          $arrayDataName.setNullAt($iterName);
        } else {
          $arrayDataName.set$primitiveValueTypeName($iterName, ${eval.value});
        }
      }"""}

    (s"""
      byte[] $arrayName = new byte[$unsafeArraySizeInBytes];
      final UnsafeArrayData $arrayDataName = new UnsafeArrayData();
      Platform.putLong($arrayName, $baseOffset, $size);
      $arrayDataName.pointTo($arrayName, $baseOffset, $unsafeArraySizeInBytes);
      """,
      assignments,
      "",
      arrayDataName)
  }
}

case class CreateLocalStruct(childrenWithoutName: Seq[Expression]) extends CreateNamedStructLike {

  override val children: Seq[Expression] = childrenWithoutName.zipWithIndex.flatMap {
    case (e, index) => Seq(Literal(s"col${index + 1}"), e)
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val rowClass = classOf[GenericInternalRow].getName
    val values = "local_values"

    ev.copy(code = s"""
      Object[] $values = new Object[${valExprs.size}];""" +
      ctx.splitExpressions(
        ctx.INPUT_ROW,
        valExprs.zipWithIndex.map { case (e, i) =>
          val eval = e.genCode(ctx)
          eval.code + s"""
            $values[$i] = ${eval.value};
          """
        }) +
      s"""
        final InternalRow ${ev.value} = new $rowClass($values);
      """, isNull = "false")
  }

  override def deterministic: Boolean = false

  override def prettyName: String = "local_struct"
}

case class SetArrayItem(child: Expression, ordinal: Expression, value: Expression)
  extends TernaryExpression with NonSQLExpression {

  override def children: Seq[Expression] = child :: ordinal :: value :: Nil

  override def deterministic: Boolean = false

  lazy val childSchema: ArrayType = child.dataType.asInstanceOf[ArrayType]

  override def dataType: DataType = BooleanType
  override def nullable: Boolean = false

  override def toString: String = {
    s"$child[$ordinal] = $value"
  }

  protected override def nullSafeEval(input: Any, ordinal: Any, value: Any): Any = {
    input.asInstanceOf[UnsafeArrayData].update(ordinal.asInstanceOf[Integer], value)
    true
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {

    val primitiveValueTypeName = child match {
      case AttributeReference(_, ArrayType(a, _), _, _) => ctx.primitiveTypeName(a)
      case _ => child.dataType match {
        case ArrayType(a, _) => ctx.primitiveTypeName(a)
      }
    }

    nullSafeCodeGen(ctx, ev, (eval1, eval2, eval3) => {
      s"""
        $eval1.set$primitiveValueTypeName($eval2, $eval3);
        ${ev.value} = true;
      """
    })
  }
}

case class GetArraySize(child: Expression) extends UnaryExpression with NonSQLExpression {

  override def nullable: Boolean = false

  override def dataType: DataType = IntegerType

  protected override def nullSafeEval(value: Any): Any = {
    val baseValue = value.asInstanceOf[ArrayData]
    baseValue.numElements()
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    nullSafeCodeGen(ctx, ev, (eval1) =>
      s"""
        ${ev.isNull} = false;
        ${ev.value} = $eval1.numElements();
      """
    )
  }
}

/**
  * Helper function (like clojure's 'do'), return the result of the last expression
  * @see https://clojuredocs.org/clojure.core/do
  */
case class Do(children: Seq[Expression]) extends Expression with NonSQLExpression {

  override def nullable: Boolean = false

  override def dataType: DataType = children.last.dataType

  override def eval(input: InternalRow): Any = {
    children.map(_.eval(input)).last
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val evals = children.map(_.genCode(ctx))
    val code = evals.map(_.code).mkString("\n/* --doseq-- */\n")

    ev.copy(code = code + s"""
      ${ctx.javaType(dataType)} ${ev.value} = ${ctx.defaultValue(dataType)};
      final boolean ${ev.isNull} = ${evals.last.isNull};
      ${ev.value} = ${evals.last.value};
      """, isNull = "false")
  }
}

case class GetArrayItemWithSize(size: Int, child: Expression, ordinal: Expression)
  extends BinaryExpression with ExpectsInputTypes with ExtractValue {

  override def inputTypes: Seq[DataType] = Seq(IntegerType, IntegerType)

  override def toString: String = s"$child[$ordinal]"
  override def sql: String = s"${child.sql}[${ordinal.sql}]"

  override def left: Expression = child
  override def right: Expression = ordinal

  /** `Null` is returned for invalid ordinals. */
  override def nullable: Boolean = true

  override def dataType: DataType = child.dataType.asInstanceOf[ArrayType].elementType

  protected override def nullSafeEval(value: Any, ordinal: Any): Any = {
    val baseValue = value.asInstanceOf[ArrayData]
    val index = ordinal.asInstanceOf[Number].intValue()
    if (index >= baseValue.numElements() || index < 0 || baseValue.isNullAt(index)) {
      null
    } else {
      baseValue.get(index, dataType)
    }
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    nullSafeCodeGen(ctx, ev, (eval1, eval2) => {
      val index = ctx.freshName("index")
      s"""
        final int $index = (int) $eval2;
        if ($index >= $size) {
          ${ev.isNull} = true;
        } else {
          ${ev.value} = ${ctx.getValue(eval1, dataType, index)};
        }
      """
    })
  }
}

object DoSeq {
  def apply(exprs: Expression*): Expression = Do(Seq(exprs: _*))
}

object Then {
  def apply(exprs: Expression*): Expression = DoSeq(exprs: _*)
}

object Else {
  def apply(exprs: Expression*): Expression = DoSeq(exprs: _*)
}

object UDFUtils {
  def makeIter(name: String): IterRef = {
    val iname = makeIterName(name)
    IterRef(iname)
  }

  def makeIterName(name: String): String = {
    name + "_iter_" + (System.currentTimeMillis() % 100000)
  }
}