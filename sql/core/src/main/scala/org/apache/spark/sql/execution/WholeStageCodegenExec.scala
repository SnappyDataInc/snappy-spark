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
package org.apache.spark.sql.execution

import java.util.Locale
import java.util.function.Supplier

import scala.collection.mutable
import scala.util.control.NonFatal

import com.esotericsoftware.kryo.{Kryo, KryoSerializable}
import com.esotericsoftware.kryo.io.{Input, Output}
import com.google.common.cache.CacheBuilder
import java.sql.SQLException
import java.util.concurrent.TimeUnit

import org.apache.spark.{broadcast, Partition, SparkContext, TaskContext}
import org.apache.spark.rdd.{RDD, ZippedPartitionsBaseRDD, ZippedPartitionsPartition}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.expressions.codegen.Block._
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.aggregate.HashAggregateExec
import org.apache.spark.sql.execution.joins.{BroadcastHashJoinExec, SortMergeJoinExec}
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.Utils

/**
 * An interface for those physical operators that support codegen.
 */
trait CodegenSupport extends SparkPlan {

  /** Prefix used in the current operator's variable names. */
  private def variablePrefix: String = this match {
    case _: HashAggregateExec => "agg"
    case _: BroadcastHashJoinExec => "bhj"
    case _: SortMergeJoinExec => "smj"
    case _: RDDScanExec => "rdd"
    case _: DataSourceScanExec => "scan"
    case _ => nodeName.toLowerCase(Locale.ROOT)
  }

  /**
   * Creates a metric using the specified name.
   *
   * @return name of the variable representing the metric
   */
  def metricTerm(ctx: CodegenContext, name: String): String = {
    ctx.addReferenceObj(name, longMetric(name))
  }

  /**
   * Whether this SparkPlan supports whole stage codegen or not.
   */
  def supportCodegen: Boolean = true

  /**
   * Which SparkPlan is calling produce() of this one. It's itself for the first SparkPlan.
   */
  protected var parent: CodegenSupport = null

  /**
   * Returns all the RDDs of InternalRow which generates the input rows.
   *
   * @note Right now we support up to two RDDs
   */
  def inputRDDs(): Seq[RDD[InternalRow]]

  /**
   * Returns Java source code to process the rows from input RDD.
   */
  final def produce(ctx: CodegenContext, parent: CodegenSupport): String = executeQuery {
    this.parent = parent
    ctx.freshNamePrefix = variablePrefix
    s"""
       |${ctx.registerComment(s"PRODUCE: ${this.simpleString}")}
       |${doProduce(ctx)}
     """.stripMargin
  }

  /**
   * Generate the Java source code to process, should be overridden by subclass to support codegen.
   *
   * doProduce() usually generate the framework, for example, aggregation could generate this:
   *
   *   if (!initialized) {
   *     # create a hash map, then build the aggregation hash map
   *     # call child.produce()
   *     initialized = true;
   *   }
   *   while (hashmap.hasNext()) {
   *     row = hashmap.next();
   *     # build the aggregation results
   *     # create variables for results
   *     # call consume(), which will call parent.doConsume()
   *      if (shouldStop()) return;
   *   }
   */
  protected def doProduce(ctx: CodegenContext): String

  private def prepareRowVar(ctx: CodegenContext, row: String, colVars: Seq[ExprCode]): ExprCode = {
    if (row != null) {
      ExprCode.forNonNullValue(JavaCode.variable(row, classOf[UnsafeRow]))
    } else {
      if (colVars.nonEmpty) {
        val colExprs = output.zipWithIndex.map { case (attr, i) =>
          BoundReference(i, attr.dataType, attr.nullable)
        }
        val evaluateInputs = evaluateVariables(colVars)
        // generate the code to create a UnsafeRow
        ctx.INPUT_ROW = row
        ctx.currentVars = colVars
        val ev = GenerateUnsafeProjection.createCode(ctx, colExprs, false)
        val code = code"""
          |$evaluateInputs
          |${ev.code}
         """.stripMargin
        ExprCode(code, FalseLiteral, ev.value)
      } else {
        // There are no columns
        ExprCode.forNonNullValue(JavaCode.variable("unsafeRow", classOf[UnsafeRow]))
      }
    }
  }

  /**
   * Consume the generated columns or row from current SparkPlan, call its parent's `doConsume()`.
   *
   * Note that `outputVars` and `row` can't both be null.
   */
  final def consume(ctx: CodegenContext, outputVars: Seq[ExprCode], row: String = null): String = {
    val inputVarsCandidate =
      if (outputVars != null) {
        assert(outputVars.length == output.length)
        // outputVars will be used to generate the code for UnsafeRow, so we should copy them
        outputVars.map(_.copy())
      } else {
        assert(row != null, "outputVars and row cannot both be null.")
        ctx.currentVars = null
        ctx.INPUT_ROW = row
        output.zipWithIndex.map { case (attr, i) =>
          BoundReference(i, attr.dataType, attr.nullable).genCode(ctx)
        }
      }

    val inputVars = inputVarsCandidate match {
      case stream: Stream[ExprCode] => stream.force
      case other => other
    }

    val rowVar = prepareRowVar(ctx, row, outputVars)

    // Set up the `currentVars` in the codegen context, as we generate the code of `inputVars`
    // before calling `parent.doConsume`. We can't set up `INPUT_ROW`, because parent needs to
    // generate code of `rowVar` manually.
    ctx.currentVars = inputVars
    ctx.INPUT_ROW = null
    ctx.freshNamePrefix = parent.variablePrefix
    val evaluated = evaluateRequiredVariables(output, inputVars, parent.usedInputs)

    // Under certain conditions, we can put the logic to consume the rows of this operator into
    // another function. So we can prevent a generated function too long to be optimized by JIT.
    // The conditions:
    // 1. The config "spark.sql.codegen.splitConsumeFuncByOperator" is enabled.
    // 2. `inputVars` are all materialized. That is guaranteed to be true if the parent plan uses
    //    all variables in output (see `requireAllOutput`).
    // 3. The number of output variables must less than maximum number of parameters in Java method
    //    declaration.
    val confEnabled = SQLConf.get.wholeStageSplitConsumeFuncByOperator
    val requireAllOutput = output.forall(parent.usedInputs.contains(_))
    val paramLength = CodeGenerator.calculateParamLength(output) + (if (row != null) 1 else 0)
    val consumeFunc = if (confEnabled && requireAllOutput
        && CodeGenerator.isValidParamLength(paramLength)) {
      constructDoConsumeFunction(ctx, inputVars, row)
    } else {
      parent.doConsume(ctx, inputVars, rowVar)
    }
    s"""
       |${ctx.registerComment(s"CONSUME: ${parent.simpleString}")}
       |$evaluated
       |$consumeFunc
     """.stripMargin
  }

  /**
   * To prevent concatenated function growing too long to be optimized by JIT. We can separate the
   * parent's `doConsume` codes of a `CodegenSupport` operator into a function to call.
   */
  private def constructDoConsumeFunction(
      ctx: CodegenContext,
      inputVars: Seq[ExprCode],
      row: String): String = {
    val (args, params, inputVarsInFunc) = constructConsumeParameters(ctx, output, inputVars, row)
    val rowVar = prepareRowVar(ctx, row, inputVarsInFunc)

    val doConsume = ctx.freshName("doConsume")
    ctx.currentVars = inputVarsInFunc
    ctx.INPUT_ROW = null

    val doConsumeFuncName = ctx.addNewFunction(doConsume,
      s"""
         | private void $doConsume(${params.mkString(", ")}) throws java.io.IOException {
         |   ${parent.doConsume(ctx, inputVarsInFunc, rowVar)}
         | }
       """.stripMargin)

    s"""
       | $doConsumeFuncName(${args.mkString(", ")});
     """.stripMargin
  }

  /**
   * Returns arguments for calling method and method definition parameters of the consume function.
   * And also returns the list of `ExprCode` for the parameters.
   */
  private def constructConsumeParameters(
      ctx: CodegenContext,
      attributes: Seq[Attribute],
      variables: Seq[ExprCode],
      row: String): (Seq[String], Seq[String], Seq[ExprCode]) = {
    val arguments = mutable.ArrayBuffer[String]()
    val parameters = mutable.ArrayBuffer[String]()
    val paramVars = mutable.ArrayBuffer[ExprCode]()

    if (row != null) {
      arguments += row
      parameters += s"InternalRow $row"
    }

    variables.zipWithIndex.foreach { case (ev, i) =>
      val paramName = ctx.freshName(s"expr_$i")
      val paramType = CodeGenerator.javaType(attributes(i).dataType)

      arguments += ev.value
      parameters += s"$paramType $paramName"
      val paramIsNull = if (!attributes(i).nullable) {
        // Use constant `false` without passing `isNull` for non-nullable variable.
        FalseLiteral
      } else {
        val isNull = ctx.freshName(s"exprIsNull_$i")
        arguments += ev.isNull
        parameters += s"boolean $isNull"
        JavaCode.isNullVariable(isNull)
      }

      paramVars += ExprCode(paramIsNull, JavaCode.variable(paramName, attributes(i).dataType))
    }
    (arguments, parameters, paramVars)
  }

  /**
   * Returns source code to evaluate all the variables, and clear the code of them, to prevent
   * them to be evaluated twice.
   */
  protected def evaluateVariables(variables: Seq[ExprCode]): String = {
    val evaluate = variables.filter(_.code.nonEmpty).map(_.code.toString).mkString("\n")
    variables.foreach(_.code = EmptyBlock)
    evaluate
  }

  /**
   * Returns source code to evaluate the variables for required attributes, and clear the code
   * of evaluated variables, to prevent them to be evaluated twice.
   */
  protected def evaluateRequiredVariables(
      attributes: Seq[Attribute],
      variables: Seq[ExprCode],
      required: AttributeSet): String = {
    val evaluateVars = new StringBuilder
    variables.zipWithIndex.foreach { case (ev, i) =>
      if (ev.code.nonEmpty && required.contains(attributes(i))) {
        evaluateVars.append(ev.code.toString + "\n")
        ev.code = EmptyBlock
      }
    }
    evaluateVars.toString()
  }

  /**
   * Returns source code to evaluate the variables for non-deterministic expressions, and clear the
   * code of evaluated variables, to prevent them to be evaluated twice.
   */
  protected def evaluateNondeterministicVariables(
      attributes: Seq[Attribute],
      variables: Seq[ExprCode],
      expressions: Seq[NamedExpression]): String = {
    val nondeterministicAttrs = expressions.filterNot(_.deterministic).map(_.toAttribute)
    evaluateRequiredVariables(attributes, variables, AttributeSet(nondeterministicAttrs))
  }

  /**
   * The subset of inputSet those should be evaluated before this plan.
   *
   * We will use this to insert some code to access those columns that are actually used by current
   * plan before calling doConsume().
   */
  def usedInputs: AttributeSet = references

  /**
   * Generate the Java source code to process the rows from child SparkPlan. This should only be
   * called from `consume`.
   *
   * This should be override by subclass to support codegen.
   *
   * Note: The operator should not assume the existence of an outer processing loop,
   *       which it can jump from with "continue;"!
   *
   * For example, filter could generate this:
   *   # code to evaluate the predicate expression, result is isNull1 and value2
   *   if (!isNull1 && value2) {
   *     # call consume(), which will call parent.doConsume()
   *   }
   *
   * Note: A plan can either consume the rows as UnsafeRow (row), or a list of variables (input).
   *       When consuming as a listing of variables, the code to produce the input is already
   *       generated and `CodegenContext.currentVars` is already set. When consuming as UnsafeRow,
   *       implementations need to put `row.code` in the generated code and set
   *       `CodegenContext.INPUT_ROW` manually. Some plans may need more tweaks as they have
   *       different inputs(join build side, aggregate buffer, etc.), or other special cases.
   */
  def doConsume(ctx: CodegenContext, input: Seq[ExprCode], row: ExprCode): String = {
    throw new UnsupportedOperationException
  }

  /**
   * Whether or not the result rows of this operator should be copied before putting into a buffer.
   *
   * If any operator inside WholeStageCodegen generate multiple rows from a single row (for
   * example, Join), this should be true.
   *
   * If an operator starts a new pipeline, this should be false.
   */
  def needCopyResult: Boolean = {
    if (children.isEmpty) {
      false
    } else if (children.length == 1) {
      children.head.asInstanceOf[CodegenSupport].needCopyResult
    } else {
      throw new UnsupportedOperationException
    }
  }

  /**
   * Whether or not the children of this operator should generate a stop check when consuming input
   * rows. This is used to suppress shouldStop() in a loop of WholeStageCodegen.
   *
   * This should be false if an operator starts a new pipeline, which means it consumes all rows
   * produced by children but doesn't output row to buffer by calling append(),  so the children
   * don't require shouldStop() in the loop of producing rows.
   */
  def needStopCheck: Boolean = parent.needStopCheck
}


/**
 * InputAdapter is used to hide a SparkPlan from a subtree that supports codegen.
 *
 * This is the leaf node of a tree with WholeStageCodegen that is used to generate code
 * that consumes an RDD iterator of InternalRow.
 */
case class InputAdapter(child: SparkPlan) extends UnaryExecNode with CodegenSupport {

  override def output: Seq[Attribute] = child.output

  override def outputPartitioning: Partitioning = child.outputPartitioning

  override def outputOrdering: Seq[SortOrder] = child.outputOrdering

  override def doExecute(): RDD[InternalRow] = {
    child.execute()
  }

  override def doExecuteBroadcast[T](): broadcast.Broadcast[T] = {
    child.doExecuteBroadcast()
  }

  override def inputRDDs(): Seq[RDD[InternalRow]] = {
    child.execute() :: Nil
  }

  override def doProduce(ctx: CodegenContext): String = {
    // Right now, InputAdapter is only used when there is one input RDD.
    // Inline mutable state since an InputAdapter is used once in a task for WholeStageCodegen
    val input = ctx.addMutableState("scala.collection.Iterator", "input", v => s"$v = inputs[0];",
      forceInline = true)
    val row = ctx.freshName("row")
    s"""
       | while ($input.hasNext() && !stopEarly()) {
       |   InternalRow $row = (InternalRow) $input.next();
       |   ${consume(ctx, null, row).trim}
       |   if (shouldStop()) return;
       | }
     """.stripMargin
  }

  override def generateTreeString(
      depth: Int,
      lastChildren: Seq[Boolean],
      builder: StringBuilder,
      verbose: Boolean,
      prefix: String = "",
      addSuffix: Boolean = false): StringBuilder = {
    child.generateTreeString(depth, lastChildren, builder, verbose, "")
  }

  override def needCopyResult: Boolean = false
}

object WholeStageCodegenExec {
  val PIPELINE_DURATION_METRIC = "duration"

  private def numOfNestedFields(dataType: DataType): Int = dataType match {
    case dt: StructType => dt.fields.map(f => numOfNestedFields(f.dataType)).sum
    case m: MapType => numOfNestedFields(m.keyType) + numOfNestedFields(m.valueType)
    case a: ArrayType => numOfNestedFields(a.elementType)
    case u: UserDefinedType[_] => numOfNestedFields(u.sqlType)
    case _ => 1
  }

  def isTooManyFields(conf: SQLConf, dataType: DataType): Boolean = {
    numOfNestedFields(dataType) > conf.wholeStageMaxNumFields
  }

  private[sql] lazy val dumpGenCodeForException: Boolean =
    SQLConf.get.getConfString("spark.sql.codegen.dump", "true").toBoolean

  private[sql] lazy val dumpedGenCodes = CacheBuilder.newBuilder().maximumSize(20)
      .expireAfterWrite(60, TimeUnit.SECONDS).build[CodeAndComment, java.lang.Boolean]()
}

object WholeStageCodegenId {
  // codegenStageId: ID for codegen stages within a query plan.
  // It does not affect equality, nor does it participate in destructuring pattern matching
  // of WholeStageCodegenExec.
  //
  // This ID is used to help differentiate between codegen stages. It is included as a part
  // of the explain output for physical plans, e.g.
  //
  // == Physical Plan ==
  // *(5) SortMergeJoin [x#3L], [y#9L], Inner
  // :- *(2) Sort [x#3L ASC NULLS FIRST], false, 0
  // :  +- Exchange hashpartitioning(x#3L, 200)
  // :     +- *(1) Project [(id#0L % 2) AS x#3L]
  // :        +- *(1) Filter isnotnull((id#0L % 2))
  // :           +- *(1) Range (0, 5, step=1, splits=8)
  // +- *(4) Sort [y#9L ASC NULLS FIRST], false, 0
  //    +- Exchange hashpartitioning(y#9L, 200)
  //       +- *(3) Project [(id#6L % 2) AS y#9L]
  //          +- *(3) Filter isnotnull((id#6L % 2))
  //             +- *(3) Range (0, 5, step=1, splits=8)
  //
  // where the ID makes it obvious that not all adjacent codegen'd plan operators are of the
  // same codegen stage.
  //
  // The codegen stage ID is also optionally included in the name of the generated classes as
  // a suffix, so that it's easier to associate a generated class back to the physical operator.
  // This is controlled by SQLConf: spark.sql.codegen.useIdInClassName
  //
  // The ID is also included in various log messages.
  //
  // Within a query, a codegen stage in a plan starts counting from 1, in "insertion order".
  // WholeStageCodegenExec operators are inserted into a plan in depth-first post-order.
  // See CollapseCodegenStages.insertWholeStageCodegen for the definition of insertion order.
  //
  // 0 is reserved as a special ID value to indicate a temporary WholeStageCodegenExec object
  // is created, e.g. for special fallback handling when an existing WholeStageCodegenExec
  // failed to generate/compile code.

  private val codegenStageCounter = ThreadLocal.withInitial(new Supplier[Integer] {
    override def get() = 1  // TODO: change to Scala lambda syntax when upgraded to Scala 2.12+
  })

  def resetPerQuery(): Unit = codegenStageCounter.set(1)

  def getNextStageId(): Int = {
    val counter = codegenStageCounter
    val id = counter.get()
    counter.set(id + 1)
    id
  }
}

/**
 * WholeStageCodegen compiles a subtree of plans that support codegen together into single Java
 * function.
 *
 * Here is the call graph of to generate Java source (plan A supports codegen, but plan B does not):
 *
 *   WholeStageCodegen       Plan A               FakeInput        Plan B
 * =========================================================================
 *
 * -> execute()
 *     |
 *  doExecute() --------->   inputRDDs() -------> inputRDDs() ------> execute()
 *     |
 *     +----------------->   produce()
 *                             |
 *                          doProduce()  -------> produce()
 *                                                   |
 *                                                doProduce()
 *                                                   |
 *                         doConsume() <--------- consume()
 *                             |
 *  doConsume()  <--------  consume()
 *
 * SparkPlan A should override `doProduce()` and `doConsume()`.
 *
 * `doCodeGen()` will create a `CodeGenContext`, which will hold a list of variables for input,
 * used to generated code for [[BoundReference]].
 */
case class WholeStageCodegenExec(child: SparkPlan)(val codegenStageId: Int)
    extends UnaryExecNode with CodegenSupport {

  override def output: Seq[Attribute] = child.output

  override def outputPartitioning: Partitioning = child.outputPartitioning

  override def outputOrdering: Seq[SortOrder] = child.outputOrdering

  override lazy val metrics = Map(
    "pipelineTime" -> SQLMetrics.createTimingMetric(sparkContext,
      WholeStageCodegenExec.PIPELINE_DURATION_METRIC))

  def generatedClassName(): String = if (conf.wholeStageUseIdInClassName) {
    s"GeneratedIteratorForCodegenStage$codegenStageId"
  } else {
    "GeneratedIterator"
  }

  /**
   * Generates code for this subtree.
   *
   * @return the tuple of the codegen context and the actual generated source.
   */
  def doCodeGen(): (CodegenContext, CodeAndComment) = {
    val ctx = new CodegenContext
    val code = child.asInstanceOf[CodegenSupport].produce(ctx, this)

    // main next function.
    ctx.addNewFunction("processNext",
      s"""
        protected void processNext() throws java.io.IOException {
          ${code.trim}
        }
       """, inlineToOuterClass = true)

    val className = generatedClassName()

    val source = s"""
      public Object generate(Object[] references) {
        return new $className(references);
      }

      ${ctx.registerComment(
        s"""Codegend pipeline for stage (id=$codegenStageId)
           |${this.treeString.trim}""".stripMargin,
         "wsc_codegenPipeline")}
      ${ctx.registerComment(s"codegenStageId=$codegenStageId", "wsc_codegenStageId", true)}
      final class $className extends ${classOf[BufferedRowIterator].getName} {

        private Object[] references;
        private scala.collection.Iterator[] inputs;
        ${ctx.declareMutableStates()}

        public $className(Object[] references) {
          this.references = references;
        }

        public void init(int index, scala.collection.Iterator[] inputs) {
          partitionIndex = index;
          this.inputs = inputs;
          ${ctx.initMutableStates()}
          ${ctx.initPartition()}
        }

        ${ctx.emitExtraCode()}

        ${ctx.declareAddedFunctions()}
      }
      """.trim

    // try to compile, helpful for debug
    val cleanedSource = CodeFormatter.stripOverlappingComments(
      new CodeAndComment(CodeFormatter.stripExtraNewLines(source), ctx.getPlaceHolderToComments()))

    logDebug(s"\n${CodeFormatter.format(cleanedSource)}")
    (ctx, cleanedSource)
  }

  override def doExecute(): RDD[InternalRow] = {
    val (ctx, cleanedSource) = doCodeGen()
    // try to compile and fallback if it failed
    val (_, maxCodeSize) = try {
      CodeGenerator.compile(cleanedSource)
    } catch {
      case NonFatal(_) if !Utils.isTesting && sqlContext.conf.codegenFallback =>
        // We should already saw the error message
        logWarning(s"Whole-stage codegen disabled for plan (id=$codegenStageId):\n $treeString")
        return child.execute()
    }

    // Check if compiled code has a too large function
    if (maxCodeSize > sqlContext.conf.hugeMethodLimit) {
      logInfo(s"Found too long generated codes and JIT optimization might not work: " +
        s"the bytecode size ($maxCodeSize) is above the limit " +
        s"${sqlContext.conf.hugeMethodLimit}, and the whole-stage codegen was disabled " +
        s"for this plan (id=$codegenStageId). To avoid this, you can raise the limit " +
        s"`${SQLConf.WHOLESTAGE_HUGE_METHOD_LIMIT.key}`:\n$treeString")
      child match {
        // The fallback solution of batch file source scan still uses WholeStageCodegenExec
        case f: FileSourceScanExec if f.supportsBatch => // do nothing
        case _ => return child.execute()
      }
    }

    val references = ctx.references.toArray

    val durationMs = longMetric("pipelineTime")

    val rdds = child.asInstanceOf[CodegenSupport].inputRDDs()
    WholeStageCodegenRDD(sqlContext.sparkContext, cleanedSource,
      references, durationMs, rdds)
  }

  override def inputRDDs(): Seq[RDD[InternalRow]] = {
    throw new UnsupportedOperationException
  }

  override def doProduce(ctx: CodegenContext): String = {
    throw new UnsupportedOperationException
  }

  override def doConsume(ctx: CodegenContext, input: Seq[ExprCode], row: ExprCode): String = {
    val doCopy = if (needCopyResult) {
      ".copy()"
    } else {
      ""
    }
    s"""
      |${row.code}
      |append(${row.value}$doCopy);
     """.stripMargin.trim
  }

  override def generateTreeString(
      depth: Int,
      lastChildren: Seq[Boolean],
      builder: StringBuilder,
      verbose: Boolean,
      prefix: String = "",
      addSuffix: Boolean = false): StringBuilder = {
    child.generateTreeString(depth, lastChildren, builder, verbose, s"*($codegenStageId) ")
  }

  override def needStopCheck: Boolean = true

  override protected def otherCopyArgs: Seq[AnyRef] = Seq(codegenStageId.asInstanceOf[Integer])
}


/**
 * Find the chained plans that support codegen, collapse them together as WholeStageCodegen.
 */
case class CollapseCodegenStages(conf: SQLConf) extends Rule[SparkPlan] {

  private def supportCodegen(e: Expression): Boolean = e match {
    case e: LeafExpression => true
    // CodegenFallback requires the input to be an InternalRow
    case e: CodegenFallback => false
    case _ => true
  }

  private def supportCodegen(plan: SparkPlan): Boolean = plan match {
    case plan: CodegenSupport if plan.supportCodegen =>
      val willFallback = plan.expressions.exists(_.find(e => !supportCodegen(e)).isDefined)
      // the generated code will be huge if there are too many columns
      val hasTooManyOutputFields =
        WholeStageCodegenExec.isTooManyFields(conf, plan.schema)
      val hasTooManyInputFields =
        plan.children.exists(p => WholeStageCodegenExec.isTooManyFields(conf, p.schema))
      !willFallback && !hasTooManyOutputFields && !hasTooManyInputFields
    case _ => false
  }

  /**
   * Inserts an InputAdapter on top of those that do not support codegen.
   */
  private def insertInputAdapter(plan: SparkPlan): SparkPlan = plan match {
    case p if !supportCodegen(p) =>
      // collapse them recursively
      InputAdapter(insertWholeStageCodegen(p))
    case j: SortMergeJoinExec =>
      // The children of SortMergeJoin should do codegen separately.
      j.withNewChildren(j.children.map(child => InputAdapter(insertWholeStageCodegen(child))))
    case p =>
      p.withNewChildren(p.children.map(insertInputAdapter))
  }

  /**
   * Inserts a WholeStageCodegen on top of those that support codegen.
   */
  private def insertWholeStageCodegen(plan: SparkPlan): SparkPlan = plan match {
    // For operators that will output domain object, do not insert WholeStageCodegen for it as
    // domain object can not be written into unsafe row.
    case plan if plan.output.length == 1 && plan.output.head.dataType.isInstanceOf[ObjectType] =>
      plan.withNewChildren(plan.children.map(insertWholeStageCodegen))
    case plan: CodegenSupport => if (supportCodegen(plan)) {
      WholeStageCodegenExec(insertInputAdapter(plan))(WholeStageCodegenId.getNextStageId())
    } else {
      plan.withNewChildren(plan.children.map(insertInputAdapter))
    }
    case other =>
      other.withNewChildren(other.children.map(insertWholeStageCodegen))
  }

  def apply(plan: SparkPlan): SparkPlan = {
    if (conf.wholeStageEnabled) {
      WholeStageCodegenId.resetPerQuery()
      insertWholeStageCodegen(plan)
    } else {
      plan
    }
  }
}

case class WholeStageCodegenRDD(@transient sc: SparkContext, var source: CodeAndComment,
    var references: Array[Any], var durationMs: SQLMetric,
    inputRDDs: Seq[RDD[InternalRow]])
    extends ZippedPartitionsBaseRDD[InternalRow](sc, inputRDDs)
        with Serializable with KryoSerializable {
  // PooledKryoSerializer.serializer refers this class using productIterator
  // Any change to this class should be reflected there.

  override def getPartitions: Array[Partition] = {
    if (rdds.length == 1) rdds.head.partitions
    else super.getPartitions
  }

  override def getPreferredLocations(s: Partition): Seq[String] = {
    if (rdds.length == 1) rdds.head.preferredLocations(s)
    else s.asInstanceOf[ZippedPartitionsPartition].preferredLocations
  }

  override def compute(split: Partition,
      context: TaskContext): Iterator[InternalRow] = {
    new Iterator[InternalRow] {
      private[this] var iter = computeInternal(split, context)

      override def hasNext: Boolean = try {
        try {
          iter.hasNext
        } catch {
          case _: ClassCastException =>
            logInfo(s"ClassCastException, hence recompiling")
            CodeGenerator.invalidate(source)
            iter = computeInternal(split, context)
            iter.hasNext
        }
      } catch {
        case e: Throwable =>
          if (WholeStageCodegenExec.dumpGenCodeForException && testNotLoggedAndSet(source)) {
            logFormattedError(e, s"\n${CodeFormatter.format(source)}")
          }
          throw e
      }

      override def next(): InternalRow = try {
        iter.next()
      } catch {
        case e: Throwable =>
          if (WholeStageCodegenExec.dumpGenCodeForException && testNotLoggedAndSet(source)) {
            logFormattedError(e, s"\n${CodeFormatter.format(source)}")
          }
          throw e
      }
    }
  }

  private def testNotLoggedAndSet(source: CodeAndComment): Boolean = {
    if (WholeStageCodegenExec.dumpedGenCodes.getIfPresent(source) eq null) {
      WholeStageCodegenExec.dumpedGenCodes.put(source, java.lang.Boolean.TRUE)
      true
    } else false
  }

  def logFormattedError(e: Throwable, source: String): Unit = {
    var cause = e
    while (cause ne null) {
      // Don't log the code when the exception is out of memory
      cause match {
        case e: SQLException if e.getSQLState == "XCL54.T" =>
          return
        case e: RuntimeException if e.getClass.getName.contains("LowMemoryException") =>
          return
        case _ =>
      }
      cause = cause.getCause
    }
    logError(s"\nFailed with exception $e:$source")
  }

  def computeInternal(split: Partition,
      context: TaskContext): Iterator[InternalRow] = {
    val clazz = CodeGenerator.compile(source)._1
    val buffer = clazz.generate(references).asInstanceOf[BufferedRowIterator]
    if (rdds.length == 1) {
      buffer.init(split.index, Array(rdds.head.iterator(split, context)
          .asInstanceOf[Iterator[InternalRow]]))
    } else {
      val zippedPartition = split.asInstanceOf[ZippedPartitionsPartition]
      val partitions = zippedPartition.partitions
      val iterators = new Array[Iterator[InternalRow]](partitions.length)
      for (i <- partitions.indices) {
        iterators(i) = rdds(i).iterator(partitions(i), context)
            .asInstanceOf[Iterator[InternalRow]]
      }
      buffer.init(zippedPartition.index, iterators)
    }
    new Iterator[InternalRow] {
      override def hasNext: Boolean = {
        val v = buffer.hasNext
        if (!v) durationMs += buffer.durationMs()
        v
      }
      override def next: InternalRow = buffer.next()
    }
  }

  override def write(kryo: Kryo, output: Output): Unit = {
    // PooledKryoSerializer.serializer refers this class using productIterator
    // Any change to this class should be reflected there.

    output.writeInt(_id)

    // write CodeAndComment
    output.writeInt(source.hashCode())
    output.writeString(source.body)
    val comment = source.comment
    output.writeInt(comment.size)
    for ((k, v) <- comment) {
      output.writeString(k)
      output.writeString(v)
    }

    val refsLen = if (references != null) references.length else 0
    output.writeVarInt(refsLen, true)
    var i = 0
    while (i < refsLen) {
      kryo.writeClassAndObject(output, references(i))
      i += 1
    }
    durationMs.write(kryo, output)

    output.writeVarInt(rdds.length, true)
    for (rdd <- rdds) {
      kryo.writeClassAndObject(output, rdd)
    }
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    _id = input.readInt()
    storageLevel = StorageLevel.NONE
    checkpointData = None

    val hash = input.readInt()
    val body = input.readString()
    var commentSize = input.readInt()
    val comment = new scala.collection.mutable.HashMap[String, String]()
    while (commentSize > 0) {
      val k = input.readString()
      val v = input.readString()
      comment.put(k, v)
      commentSize -= 1
    }
    source = new CodeAndComment(body, comment)
    source.hash = hash

    val refsLen = input.readVarInt(true)
    if (refsLen > 0) {
      references = new Array[Any](refsLen)
      var i = 0
      while (i < refsLen) {
        references(i) = kryo.readClassAndObject(input)
        i += 1
      }
    } else {
      references = null
    }
    durationMs = new SQLMetric(null)
    durationMs.read(kryo, input)

    val rddsBuilder = IndexedSeq.newBuilder[RDD[InternalRow]]
    var rddsLen = input.readVarInt(true)
    while (rddsLen > 0) {
      rddsBuilder += kryo.readClassAndObject(input).asInstanceOf[RDD[InternalRow]]
      rddsLen -= 1
    }
    rdds = rddsBuilder.result()
  }
}
