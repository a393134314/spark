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

package org.apache.spark.sql

import java.io.CharArrayWriter

import scala.collection.JavaConverters._
import scala.language.implicitConversions
import scala.reflect.runtime.universe.TypeTag

import com.fasterxml.jackson.core.JsonFactory

import org.apache.spark.annotation.{DeveloperApi, Experimental}
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.api.java.function._
import org.apache.spark.api.python.PythonRDD
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst._
import org.apache.spark.sql.catalyst.analysis._
import org.apache.spark.sql.catalyst.encoders._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.optimizer.CombineUnions
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.util.usePrettyExpression
import org.apache.spark.sql.execution.{FileRelation, LogicalRDD, Queryable, QueryExecution, SQLExecution}
import org.apache.spark.sql.execution.command.ExplainCommand
import org.apache.spark.sql.execution.datasources.{CreateTableUsingAsSelect, LogicalRelation}
import org.apache.spark.sql.execution.datasources.json.JacksonGenerator
import org.apache.spark.sql.execution.python.EvaluatePython
import org.apache.spark.sql.types._
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.Utils

private[sql] object Dataset {
  def apply[T: Encoder](sqlContext: SQLContext, logicalPlan: LogicalPlan): Dataset[T] = {
    new Dataset(sqlContext, logicalPlan, implicitly[Encoder[T]])
  }

  def newDataFrame(sqlContext: SQLContext, logicalPlan: LogicalPlan): DataFrame = {
    val qe = sqlContext.executePlan(logicalPlan)
    qe.assertAnalyzed()
    new Dataset[Row](sqlContext, logicalPlan, RowEncoder(qe.analyzed.schema))
  }
}

/**
 * A [[Dataset]] is a strongly typed collection of domain-specific objects that can be transformed
 * in parallel using functional or relational operations. Each Dataset also has an untyped view
 * called a [[DataFrame]], which is a Dataset of [[Row]].
 *
 * Operations available on Datasets are divided into transformations and actions. Transformations
 * are the ones that produce new Datasets, and actions are the ones that trigger computation and
 * return results. Example transformations include map, filter, select, and aggregate (`groupBy`).
 * Example actions count, show, or writing data out to file systems.
 *
 * Datasets are "lazy", i.e. computations are only triggered when an action is invoked. Internally,
 * a Dataset represents a logical plan that describes the computation required to produce the data.
 * When an action is invoked, Spark's query optimizer optimizes the logical plan and generates a
 * physical plan for efficient execution in a parallel and distributed manner. To explore the
 * logical plan as well as optimized physical plan, use the `explain` function.
 *
 * To efficiently support domain-specific objects, an [[Encoder]] is required. The encoder maps
 * the domain specific type `T` to Spark's internal type system. For example, given a class `Person`
 * with two fields, `name` (string) and `age` (int), an encoder is used to tell Spark to generate
 * code at runtime to serialize the `Person` object into a binary structure. This binary structure
 * often has much lower memory footprint as well as are optimized for efficiency in data processing
 * (e.g. in a columnar format). To understand the internal binary representation for data, use the
 * `schema` function.
 *
 * There are typically two ways to create a Dataset. The most common way is by pointing Spark
 * to some files on storage systems, using the `read` function available on a `SparkSession`.
 * {{{
 *   val people = session.read.parquet("...").as[Person]  // Scala
 *   Dataset<Person> people = session.read().parquet("...").as(Encoders.bean(Person.class)  // Java
 * }}}
 *
 * Datasets can also be created through transformations available on existing Datasets. For example,
 * the following creates a new Dataset by applying a filter on the existing one:
 * {{{
 *   val names = people.map(_.name)  // in Scala; names is a Dataset[String]
 *   Dataset<String> names = people.map((Person p) -> p.name, Encoders.STRING)  // in Java 8
 * }}}
 *
 * Dataset operations can also be untyped, through various domain-specific-language (DSL)
 * functions defined in: [[Dataset]] (this class), [[Column]], and [[functions]]. These operations
 * are very similar to the operations available in the data frame abstraction in R or Python.
 *
 * To select a column from the Dataset, use `apply` method in Scala and `col` in Java.
 * {{{
 *   val ageCol = people("age")  // in Scala
 *   Column ageCol = people.col("age")  // in Java
 * }}}
 *
 * Note that the [[Column]] type can also be manipulated through its various functions.
 * {{{
 *   // The following creates a new column that increases everybody's age by 10.
 *   people("age") + 10  // in Scala
 *   people.col("age").plus(10);  // in Java
 * }}}
 *
 * A more concrete example in Scala:
 * {{{
 *   // To create Dataset[Row] using SQLContext
 *   val people = session.read.parquet("...")
 *   val department = session.read.parquet("...")
 *
 *   people.filter("age > 30")
 *     .join(department, people("deptId") === department("id"))
 *     .groupBy(department("name"), "gender")
 *     .agg(avg(people("salary")), max(people("age")))
 * }}}
 *
 * and in Java:
 * {{{
 *   // To create Dataset<Row> using SQLContext
 *   Dataset<Row> people = session.read().parquet("...");
 *   Dataset<Row> department = session.read().parquet("...");
 *
 *   people.filter("age".gt(30))
 *     .join(department, people.col("deptId").equalTo(department("id")))
 *     .groupBy(department.col("name"), "gender")
 *     .agg(avg(people.col("salary")), max(people.col("age")));
 * }}}
 *
 * @groupname basic Basic Dataset functions
 * @groupname action Actions
 * @groupname untypedrel Untyped Language Integrated Relational Queries
 * @groupname typedrel Typed Language Integrated Relational Queries
 * @groupname func Functional Transformations
 * @groupname rdd RDD Operations
 * @groupname output Output Operations
 *
 * @since 1.6.0
 */
class Dataset[T] private[sql](
    @transient override val sqlContext: SQLContext,
    @DeveloperApi @transient override val queryExecution: QueryExecution,
    encoder: Encoder[T])
  extends Queryable with Serializable {

  queryExecution.assertAnalyzed()

  // Note for Spark contributors: if adding or updating any action in `Dataset`, please make sure
  // you wrap it with `withNewExecutionId` if this actions doesn't call other action.

  def this(sqlContext: SQLContext, logicalPlan: LogicalPlan, encoder: Encoder[T]) = {
    this(sqlContext, sqlContext.executePlan(logicalPlan), encoder)
  }

  @transient protected[sql] val logicalPlan: LogicalPlan = {
    def hasSideEffects(plan: LogicalPlan): Boolean = plan match {
      case _: Command |
           _: InsertIntoTable |
           _: CreateTableUsingAsSelect => true
      case _ => false
    }

    queryExecution.logical match {
      // For various commands (like DDL) and queries with side effects, we force query execution
      // to happen right away to let these side effects take place eagerly.
      case p if hasSideEffects(p) =>
        LogicalRDD(queryExecution.analyzed.output, queryExecution.toRdd)(sqlContext)
      case Union(children) if children.forall(hasSideEffects) =>
        LogicalRDD(queryExecution.analyzed.output, queryExecution.toRdd)(sqlContext)
      case _ =>
        queryExecution.analyzed
    }
  }

  /**
   * An unresolved version of the internal encoder for the type of this [[Dataset]].  This one is
   * marked implicit so that we can use it when constructing new [[Dataset]] objects that have the
   * same object type (that will be possibly resolved to a different schema).
   */
  private[sql] implicit val unresolvedTEncoder: ExpressionEncoder[T] = encoderFor(encoder)
  unresolvedTEncoder.validate(logicalPlan.output)

  /** The encoder for this [[Dataset]] that has been resolved to its output schema. */
  private[sql] val resolvedTEncoder: ExpressionEncoder[T] =
    unresolvedTEncoder.resolve(logicalPlan.output, OuterScopes.outerScopes)

  /**
   * The encoder where the expressions used to construct an object from an input row have been
   * bound to the ordinals of this [[Dataset]]'s output schema.
   */
  private[sql] val boundTEncoder = resolvedTEncoder.bind(logicalPlan.output)

  private implicit def classTag = unresolvedTEncoder.clsTag

  protected[sql] def resolve(colName: String): NamedExpression = {
    queryExecution.analyzed.resolveQuoted(colName, sqlContext.sessionState.analyzer.resolver)
      .getOrElse {
        throw new AnalysisException(
          s"""Cannot resolve column name "$colName" among (${schema.fieldNames.mkString(", ")})""")
      }
  }

  protected[sql] def numericColumns: Seq[Expression] = {
    schema.fields.filter(_.dataType.isInstanceOf[NumericType]).map { n =>
      queryExecution.analyzed.resolveQuoted(n.name, sqlContext.sessionState.analyzer.resolver).get
    }
  }

  /**
   * Compose the string representing rows for output
   *
   * @param _numRows Number of rows to show
   * @param truncate Whether truncate long strings and align cells right
   */
  override private[sql] def showString(_numRows: Int, truncate: Boolean = true): String = {
    val numRows = _numRows.max(0)
    val takeResult = take(numRows + 1)
    val hasMoreData = takeResult.length > numRows
    val data = takeResult.take(numRows)

    // For array values, replace Seq and Array with square brackets
    // For cells that are beyond 20 characters, replace it with the first 17 and "..."
    val rows: Seq[Seq[String]] = schema.fieldNames.toSeq +: data.map {
      case r: Row => r
      case tuple: Product => Row.fromTuple(tuple)
      case o => Row(o)
    }.map { row =>
      row.toSeq.map { cell =>
        val str = cell match {
          case null => "null"
          case binary: Array[Byte] => binary.map("%02X".format(_)).mkString("[", " ", "]")
          case array: Array[_] => array.mkString("[", ", ", "]")
          case seq: Seq[_] => seq.mkString("[", ", ", "]")
          case _ => cell.toString
        }
        if (truncate && str.length > 20) str.substring(0, 17) + "..." else str
      }: Seq[String]
    }

    formatString ( rows, numRows, hasMoreData, truncate )
  }

  /**
   * Converts this strongly typed collection of data to generic Dataframe.  In contrast to the
   * strongly typed objects that Dataset operations work on, a Dataframe returns generic [[Row]]
   * objects that allow fields to be accessed by ordinal or name.
   *
   * @group basic
   * @since 1.6.0
   */
  // This is declared with parentheses to prevent the Scala compiler from treating
  // `ds.toDF("1")` as invoking this toDF and then apply on the returned DataFrame.
  def toDF(): DataFrame = new Dataset[Row](sqlContext, queryExecution, RowEncoder(schema))

  /**
   * :: Experimental ::
   * Returns a new [[Dataset]] where each record has been mapped on to the specified type.  The
   * method used to map columns depend on the type of `U`:
   *  - When `U` is a class, fields for the class will be mapped to columns of the same name
   *    (case sensitivity is determined by `spark.sql.caseSensitive`)
   *  - When `U` is a tuple, the columns will be be mapped by ordinal (i.e. the first column will
   *    be assigned to `_1`).
   *  - When `U` is a primitive type (i.e. String, Int, etc). then the first column of the
   *    [[DataFrame]] will be used.
   *
   * If the schema of the [[Dataset]] does not match the desired `U` type, you can use `select`
   * along with `alias` or `as` to rearrange or rename as required.
   *
   * @group basic
   * @since 1.6.0
   */
  @Experimental
  def as[U : Encoder]: Dataset[U] = Dataset[U](sqlContext, logicalPlan)

  /**
   * Converts this strongly typed collection of data to generic `DataFrame` with columns renamed.
   * This can be quite convenient in conversion from a RDD of tuples into a [[DataFrame]] with
   * meaningful names. For example:
   * {{{
   *   val rdd: RDD[(Int, String)] = ...
   *   rdd.toDF()  // this implicit conversion creates a DataFrame with column name `_1` and `_2`
   *   rdd.toDF("id", "name")  // this creates a DataFrame with column name "id" and "name"
   * }}}
   *
   * @group basic
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def toDF(colNames: String*): DataFrame = {
    require(schema.size == colNames.size,
      "The number of columns doesn't match.\n" +
        s"Old column names (${schema.size}): " + schema.fields.map(_.name).mkString(", ") + "\n" +
        s"New column names (${colNames.size}): " + colNames.mkString(", "))

    val newCols = logicalPlan.output.zip(colNames).map { case (oldAttribute, newName) =>
      Column(oldAttribute).as(newName)
    }
    select(newCols : _*)
  }

  /**
   * Returns the schema of this [[Dataset]].
   *
   * @group basic
   * @since 1.6.0
   */
  def schema: StructType = queryExecution.analyzed.schema

  /**
   * Prints the schema to the console in a nice tree format.
   *
   * @group basic
   * @since 1.6.0
   */
  // scalastyle:off println
  override def printSchema(): Unit = println(schema.treeString)
  // scalastyle:on println

  /**
   * Prints the plans (logical and physical) to the console for debugging purposes.
   *
   * @group basic
   * @since 1.6.0
   */
  override def explain(extended: Boolean): Unit = {
    val explain = ExplainCommand(queryExecution.logical, extended = extended)
    sqlContext.executePlan(explain).executedPlan.executeCollect().foreach {
      // scalastyle:off println
      r => println(r.getString(0))
      // scalastyle:on println
    }
  }

  /**
   * Prints the physical plan to the console for debugging purposes.
   *
   * @group basic
   * @since 1.6.0
   */
  override def explain(): Unit = explain(extended = false)

  /**
   * Returns all column names and their data types as an array.
   *
   * @group basic
   * @since 1.6.0
   */
  def dtypes: Array[(String, String)] = schema.fields.map { field =>
    (field.name, field.dataType.toString)
  }

  /**
   * Returns all column names as an array.
   *
   * @group basic
   * @since 1.6.0
   */
  def columns: Array[String] = schema.fields.map(_.name)

  /**
   * Returns true if the `collect` and `take` methods can be run locally
   * (without any Spark executors).
   *
   * @group basic
   * @since 1.6.0
   */
  def isLocal: Boolean = logicalPlan.isInstanceOf[LocalRelation]

  /**
   * Displays the [[Dataset]] in a tabular form. Strings more than 20 characters will be truncated,
   * and all cells will be aligned right. For example:
   * {{{
   *   year  month AVG('Adj Close) MAX('Adj Close)
   *   1980  12    0.503218        0.595103
   *   1981  01    0.523289        0.570307
   *   1982  02    0.436504        0.475256
   *   1983  03    0.410516        0.442194
   *   1984  04    0.450090        0.483521
   * }}}
   *
   * @param numRows Number of rows to show
   *
   * @group action
   * @since 1.6.0
   */
  def show(numRows: Int): Unit = show(numRows, truncate = true)

  /**
   * Displays the top 20 rows of [[Dataset]] in a tabular form. Strings more than 20 characters
   * will be truncated, and all cells will be aligned right.
   *
   * @group action
   * @since 1.6.0
   */
  def show(): Unit = show(20)

  /**
   * Displays the top 20 rows of [[Dataset]] in a tabular form.
   *
   * @param truncate Whether truncate long strings. If true, strings more than 20 characters will
   *                 be truncated and all cells will be aligned right
   *
   * @group action
   * @since 1.6.0
   */
  def show(truncate: Boolean): Unit = show(20, truncate)

  /**
   * Displays the [[Dataset]] in a tabular form. For example:
   * {{{
   *   year  month AVG('Adj Close) MAX('Adj Close)
   *   1980  12    0.503218        0.595103
   *   1981  01    0.523289        0.570307
   *   1982  02    0.436504        0.475256
   *   1983  03    0.410516        0.442194
   *   1984  04    0.450090        0.483521
   * }}}
   * @param numRows Number of rows to show
   * @param truncate Whether truncate long strings. If true, strings more than 20 characters will
   *              be truncated and all cells will be aligned right
   *
   * @group action
   * @since 1.6.0
   */
  // scalastyle:off println
  def show(numRows: Int, truncate: Boolean): Unit = println(showString(numRows, truncate))
  // scalastyle:on println

  /**
   * Returns a [[DataFrameNaFunctions]] for working with missing data.
   * {{{
   *   // Dropping rows containing any null values.
   *   ds.na.drop()
   * }}}
   *
   * @group untypedrel
   * @since 1.6.0
   */
  def na: DataFrameNaFunctions = new DataFrameNaFunctions(toDF())

  /**
   * Returns a [[DataFrameStatFunctions]] for working statistic functions support.
   * {{{
   *   // Finding frequent items in column with name 'a'.
   *   ds.stat.freqItems(Seq("a"))
   * }}}
   *
   * @group untypedrel
   * @since 1.6.0
   */
  def stat: DataFrameStatFunctions = new DataFrameStatFunctions(toDF())

  /**
   * Cartesian join with another [[DataFrame]].
   *
   * Note that cartesian joins are very expensive without an extra filter that can be pushed down.
   *
   * @param right Right side of the join operation.
   *
   * @group untypedrel
   * @since 2.0.0
   */
  def join(right: DataFrame): DataFrame = withPlan {
    Join(logicalPlan, right.logicalPlan, joinType = Inner, None)
  }

  /**
   * Inner equi-join with another [[DataFrame]] using the given column.
   *
   * Different from other join functions, the join column will only appear once in the output,
   * i.e. similar to SQL's `JOIN USING` syntax.
   *
   * {{{
   *   // Joining df1 and df2 using the column "user_id"
   *   df1.join(df2, "user_id")
   * }}}
   *
   * Note that if you perform a self-join using this function without aliasing the input
   * [[DataFrame]]s, you will NOT be able to reference any columns after the join, since
   * there is no way to disambiguate which side of the join you would like to reference.
   *
   * @param right Right side of the join operation.
   * @param usingColumn Name of the column to join on. This column must exist on both sides.
   *
   * @group untypedrel
   * @since 2.0.0
   */
  def join(right: DataFrame, usingColumn: String): DataFrame = {
    join(right, Seq(usingColumn))
  }

  /**
   * Inner equi-join with another [[DataFrame]] using the given columns.
   *
   * Different from other join functions, the join columns will only appear once in the output,
   * i.e. similar to SQL's `JOIN USING` syntax.
   *
   * {{{
   *   // Joining df1 and df2 using the columns "user_id" and "user_name"
   *   df1.join(df2, Seq("user_id", "user_name"))
   * }}}
   *
   * Note that if you perform a self-join using this function without aliasing the input
   * [[DataFrame]]s, you will NOT be able to reference any columns after the join, since
   * there is no way to disambiguate which side of the join you would like to reference.
   *
   * @param right Right side of the join operation.
   * @param usingColumns Names of the columns to join on. This columns must exist on both sides.
   *
   * @group untypedrel
   * @since 2.0.0
   */
  def join(right: DataFrame, usingColumns: Seq[String]): DataFrame = {
    join(right, usingColumns, "inner")
  }

  /**
   * Equi-join with another [[DataFrame]] using the given columns.
   *
   * Different from other join functions, the join columns will only appear once in the output,
   * i.e. similar to SQL's `JOIN USING` syntax.
   *
   * Note that if you perform a self-join using this function without aliasing the input
   * [[DataFrame]]s, you will NOT be able to reference any columns after the join, since
   * there is no way to disambiguate which side of the join you would like to reference.
   *
   * @param right Right side of the join operation.
   * @param usingColumns Names of the columns to join on. This columns must exist on both sides.
   * @param joinType One of: `inner`, `outer`, `left_outer`, `right_outer`, `leftsemi`.
   *
   * @group untypedrel
   * @since 2.0.0
   */
  def join(right: DataFrame, usingColumns: Seq[String], joinType: String): DataFrame = {
    // Analyze the self join. The assumption is that the analyzer will disambiguate left vs right
    // by creating a new instance for one of the branch.
    val joined = sqlContext.executePlan(
      Join(logicalPlan, right.logicalPlan, joinType = JoinType(joinType), None))
      .analyzed.asInstanceOf[Join]

    withPlan {
      Join(
        joined.left,
        joined.right,
        UsingJoin(JoinType(joinType), usingColumns.map(UnresolvedAttribute(_))),
        None)
    }
  }

  /**
   * Inner join with another [[DataFrame]], using the given join expression.
   *
   * {{{
   *   // The following two are equivalent:
   *   df1.join(df2, $"df1Key" === $"df2Key")
   *   df1.join(df2).where($"df1Key" === $"df2Key")
   * }}}
   *
   * @group untypedrel
   * @since 2.0.0
   */
  def join(right: DataFrame, joinExprs: Column): DataFrame = join(right, joinExprs, "inner")

  /**
   * Join with another [[DataFrame]], using the given join expression. The following performs
   * a full outer join between `df1` and `df2`.
   *
   * {{{
   *   // Scala:
   *   import org.apache.spark.sql.functions._
   *   df1.join(df2, $"df1Key" === $"df2Key", "outer")
   *
   *   // Java:
   *   import static org.apache.spark.sql.functions.*;
   *   df1.join(df2, col("df1Key").equalTo(col("df2Key")), "outer");
   * }}}
   *
   * @param right Right side of the join.
   * @param joinExprs Join expression.
   * @param joinType One of: `inner`, `outer`, `left_outer`, `right_outer`, `leftsemi`.
   *
   * @group untypedrel
   * @since 2.0.0
   */
  def join(right: DataFrame, joinExprs: Column, joinType: String): DataFrame = {
    // Note that in this function, we introduce a hack in the case of self-join to automatically
    // resolve ambiguous join conditions into ones that might make sense [SPARK-6231].
    // Consider this case: df.join(df, df("key") === df("key"))
    // Since df("key") === df("key") is a trivially true condition, this actually becomes a
    // cartesian join. However, most likely users expect to perform a self join using "key".
    // With that assumption, this hack turns the trivially true condition into equality on join
    // keys that are resolved to both sides.

    // Trigger analysis so in the case of self-join, the analyzer will clone the plan.
    // After the cloning, left and right side will have distinct expression ids.
    val plan = withPlan(
      Join(logicalPlan, right.logicalPlan, JoinType(joinType), Some(joinExprs.expr)))
      .queryExecution.analyzed.asInstanceOf[Join]

    // If auto self join alias is disabled, return the plan.
    if (!sqlContext.conf.dataFrameSelfJoinAutoResolveAmbiguity) {
      return withPlan(plan)
    }

    // If left/right have no output set intersection, return the plan.
    val lanalyzed = withPlan(this.logicalPlan).queryExecution.analyzed
    val ranalyzed = withPlan(right.logicalPlan).queryExecution.analyzed
    if (lanalyzed.outputSet.intersect(ranalyzed.outputSet).isEmpty) {
      return withPlan(plan)
    }

    // Otherwise, find the trivially true predicates and automatically resolves them to both sides.
    // By the time we get here, since we have already run analysis, all attributes should've been
    // resolved and become AttributeReference.
    val cond = plan.condition.map { _.transform {
      case catalyst.expressions.EqualTo(a: AttributeReference, b: AttributeReference)
          if a.sameRef(b) =>
        catalyst.expressions.EqualTo(
          withPlan(plan.left).resolve(a.name),
          withPlan(plan.right).resolve(b.name))
    }}

    withPlan {
      plan.copy(condition = cond)
    }
  }

  /**
   * :: Experimental ::
   * Joins this [[Dataset]] returning a [[Tuple2]] for each pair where `condition` evaluates to
   * true.
   *
   * This is similar to the relation `join` function with one important difference in the
   * result schema. Since `joinWith` preserves objects present on either side of the join, the
   * result schema is similarly nested into a tuple under the column names `_1` and `_2`.
   *
   * This type of join can be useful both for preserving type-safety with the original object
   * types as well as working with relational data where either side of the join has column
   * names in common.
   *
   * @param other Right side of the join.
   * @param condition Join expression.
   * @param joinType One of: `inner`, `outer`, `left_outer`, `right_outer`, `leftsemi`.
   *
   * @group typedrel
   * @since 1.6.0
   */
  @Experimental
  def joinWith[U](other: Dataset[U], condition: Column, joinType: String): Dataset[(T, U)] = {
    val left = this.logicalPlan
    val right = other.logicalPlan

    val joined = sqlContext.executePlan(Join(left, right, joinType =
      JoinType(joinType), Some(condition.expr)))
    val leftOutput = joined.analyzed.output.take(left.output.length)
    val rightOutput = joined.analyzed.output.takeRight(right.output.length)

    val leftData = this.unresolvedTEncoder match {
      case e if e.flat => Alias(leftOutput.head, "_1")()
      case _ => Alias(CreateStruct(leftOutput), "_1")()
    }
    val rightData = other.unresolvedTEncoder match {
      case e if e.flat => Alias(rightOutput.head, "_2")()
      case _ => Alias(CreateStruct(rightOutput), "_2")()
    }

    implicit val tuple2Encoder: Encoder[(T, U)] =
      ExpressionEncoder.tuple(this.unresolvedTEncoder, other.unresolvedTEncoder)
    withTypedPlan[(T, U)](other, encoderFor[(T, U)]) { (left, right) =>
      Project(
        leftData :: rightData :: Nil,
        joined.analyzed)
    }
  }

  /**
   * :: Experimental ::
   * Using inner equi-join to join this [[Dataset]] returning a [[Tuple2]] for each pair
   * where `condition` evaluates to true.
   *
   * @param other Right side of the join.
   * @param condition Join expression.
   *
   * @group typedrel
   * @since 1.6.0
   */
  @Experimental
  def joinWith[U](other: Dataset[U], condition: Column): Dataset[(T, U)] = {
    joinWith(other, condition, "inner")
  }

  /**
   * Returns a new [[Dataset]] with each partition sorted by the given expressions.
   *
   * This is the same operation as "SORT BY" in SQL (Hive QL).
   *
   * @group typedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def sortWithinPartitions(sortCol: String, sortCols: String*): Dataset[T] = {
    sortWithinPartitions((sortCol +: sortCols).map(Column(_)) : _*)
  }

  /**
   * Returns a new [[Dataset]] with each partition sorted by the given expressions.
   *
   * This is the same operation as "SORT BY" in SQL (Hive QL).
   *
   * @group typedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def sortWithinPartitions(sortExprs: Column*): Dataset[T] = {
    sortInternal(global = false, sortExprs)
  }

  /**
   * Returns a new [[Dataset]] sorted by the specified column, all in ascending order.
   * {{{
   *   // The following 3 are equivalent
   *   ds.sort("sortcol")
   *   ds.sort($"sortcol")
   *   ds.sort($"sortcol".asc)
   * }}}
   *
   * @group typedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def sort(sortCol: String, sortCols: String*): Dataset[T] = {
    sort((sortCol +: sortCols).map(apply) : _*)
  }

  /**
   * Returns a new [[Dataset]] sorted by the given expressions. For example:
   * {{{
   *   ds.sort($"col1", $"col2".desc)
   * }}}
   *
   * @group typedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def sort(sortExprs: Column*): Dataset[T] = {
    sortInternal(global = true, sortExprs)
  }

  /**
   * Returns a new [[Dataset]] sorted by the given expressions.
   * This is an alias of the `sort` function.
   *
   * @group typedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def orderBy(sortCol: String, sortCols: String*): Dataset[T] = sort(sortCol, sortCols : _*)

  /**
   * Returns a new [[Dataset]] sorted by the given expressions.
   * This is an alias of the `sort` function.
   *
   * @group typedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def orderBy(sortExprs: Column*): Dataset[T] = sort(sortExprs : _*)

  /**
   * Selects column based on the column name and return it as a [[Column]].
   * Note that the column name can also reference to a nested column like `a.b`.
   *
   * @group untypedrel
   * @since 2.0.0
   */
  def apply(colName: String): Column = col(colName)

  /**
   * Selects column based on the column name and return it as a [[Column]].
   * Note that the column name can also reference to a nested column like `a.b`.
   *
   * @group untypedrel
   * @since 2.0.0
   */
  def col(colName: String): Column = colName match {
    case "*" =>
      Column(ResolvedStar(queryExecution.analyzed.output))
    case _ =>
      val expr = resolve(colName)
      Column(expr)
  }

  /**
   * Returns a new [[Dataset]] with an alias set.
   *
   * @group typedrel
   * @since 1.6.0
   */
  def as(alias: String): Dataset[T] = withTypedPlan {
    SubqueryAlias(alias, logicalPlan)
  }

  /**
   * (Scala-specific) Returns a new [[Dataset]] with an alias set.
   *
   * @group typedrel
   * @since 2.0.0
   */
  def as(alias: Symbol): Dataset[T] = as(alias.name)

  /**
   * Returns a new [[Dataset]] with an alias set. Same as `as`.
   *
   * @group typedrel
   * @since 2.0.0
   */
  def alias(alias: String): Dataset[T] = as(alias)

  /**
   * (Scala-specific) Returns a new [[Dataset]] with an alias set. Same as `as`.
   *
   * @group typedrel
   * @since 2.0.0
   */
  def alias(alias: Symbol): Dataset[T] = as(alias)

  /**
   * Selects a set of column based expressions.
   * {{{
   *   ds.select($"colA", $"colB" + 1)
   * }}}
   *
   * @group untypedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def select(cols: Column*): DataFrame = withPlan {
    Project(cols.map(_.named), logicalPlan)
  }

  /**
   * Selects a set of columns. This is a variant of `select` that can only select
   * existing columns using column names (i.e. cannot construct expressions).
   *
   * {{{
   *   // The following two are equivalent:
   *   ds.select("colA", "colB")
   *   ds.select($"colA", $"colB")
   * }}}
   *
   * @group untypedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def select(col: String, cols: String*): DataFrame = select((col +: cols).map(Column(_)) : _*)

  /**
   * Selects a set of SQL expressions. This is a variant of `select` that accepts
   * SQL expressions.
   *
   * {{{
   *   // The following are equivalent:
   *   ds.selectExpr("colA", "colB as newName", "abs(colC)")
   *   ds.select(expr("colA"), expr("colB as newName"), expr("abs(colC)"))
   * }}}
   *
   * @group untypedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def selectExpr(exprs: String*): DataFrame = {
    select(exprs.map { expr =>
      Column(sqlContext.sessionState.sqlParser.parseExpression(expr))
    }: _*)
  }

  /**
   * :: Experimental ::
   * Returns a new [[Dataset]] by computing the given [[Column]] expression for each element.
   *
   * {{{
   *   val ds = Seq(1, 2, 3).toDS()
   *   val newDS = ds.select(expr("value + 1").as[Int])
   * }}}
   *
   * @group typedrel
   * @since 1.6.0
   */
  @Experimental
  def select[U1: Encoder](c1: TypedColumn[T, U1]): Dataset[U1] = {
    new Dataset[U1](
      sqlContext,
      Project(
        c1.withInputType(
          boundTEncoder,
          logicalPlan.output).named :: Nil,
        logicalPlan),
      implicitly[Encoder[U1]])
  }

  /**
   * Internal helper function for building typed selects that return tuples.  For simplicity and
   * code reuse, we do this without the help of the type system and then use helper functions
   * that cast appropriately for the user facing interface.
   */
  protected def selectUntyped(columns: TypedColumn[_, _]*): Dataset[_] = {
    val encoders = columns.map(_.encoder)
    val namedColumns =
      columns.map(_.withInputType(resolvedTEncoder, logicalPlan.output).named)
    val execution = new QueryExecution(sqlContext, Project(namedColumns, logicalPlan))

    new Dataset(sqlContext, execution, ExpressionEncoder.tuple(encoders))
  }

  /**
   * :: Experimental ::
   * Returns a new [[Dataset]] by computing the given [[Column]] expressions for each element.
   *
   * @group typedrel
   * @since 1.6.0
   */
  @Experimental
  def select[U1, U2](c1: TypedColumn[T, U1], c2: TypedColumn[T, U2]): Dataset[(U1, U2)] =
    selectUntyped(c1, c2).asInstanceOf[Dataset[(U1, U2)]]

  /**
   * :: Experimental ::
   * Returns a new [[Dataset]] by computing the given [[Column]] expressions for each element.
   *
   * @group typedrel
   * @since 1.6.0
   */
  @Experimental
  def select[U1, U2, U3](
      c1: TypedColumn[T, U1],
      c2: TypedColumn[T, U2],
      c3: TypedColumn[T, U3]): Dataset[(U1, U2, U3)] =
    selectUntyped(c1, c2, c3).asInstanceOf[Dataset[(U1, U2, U3)]]

  /**
   * :: Experimental ::
   * Returns a new [[Dataset]] by computing the given [[Column]] expressions for each element.
   *
   * @group typedrel
   * @since 1.6.0
   */
  @Experimental
  def select[U1, U2, U3, U4](
      c1: TypedColumn[T, U1],
      c2: TypedColumn[T, U2],
      c3: TypedColumn[T, U3],
      c4: TypedColumn[T, U4]): Dataset[(U1, U2, U3, U4)] =
    selectUntyped(c1, c2, c3, c4).asInstanceOf[Dataset[(U1, U2, U3, U4)]]

  /**
   * :: Experimental ::
   * Returns a new [[Dataset]] by computing the given [[Column]] expressions for each element.
   *
   * @group typedrel
   * @since 1.6.0
   */
  @Experimental
  def select[U1, U2, U3, U4, U5](
      c1: TypedColumn[T, U1],
      c2: TypedColumn[T, U2],
      c3: TypedColumn[T, U3],
      c4: TypedColumn[T, U4],
      c5: TypedColumn[T, U5]): Dataset[(U1, U2, U3, U4, U5)] =
    selectUntyped(c1, c2, c3, c4, c5).asInstanceOf[Dataset[(U1, U2, U3, U4, U5)]]

  /**
   * Filters rows using the given condition.
   * {{{
   *   // The following are equivalent:
   *   peopleDs.filter($"age" > 15)
   *   peopleDs.where($"age" > 15)
   * }}}
   *
   * @group typedrel
   * @since 1.6.0
   */
  def filter(condition: Column): Dataset[T] = withTypedPlan {
    Filter(condition.expr, logicalPlan)
  }

  /**
   * Filters rows using the given SQL expression.
   * {{{
   *   peopleDs.filter("age > 15")
   * }}}
   *
   * @group typedrel
   * @since 1.6.0
   */
  def filter(conditionExpr: String): Dataset[T] = {
    filter(Column(sqlContext.sessionState.sqlParser.parseExpression(conditionExpr)))
  }

  /**
   * Filters rows using the given condition. This is an alias for `filter`.
   * {{{
   *   // The following are equivalent:
   *   peopleDs.filter($"age" > 15)
   *   peopleDs.where($"age" > 15)
   * }}}
   *
   * @group typedrel
   * @since 1.6.0
   */
  def where(condition: Column): Dataset[T] = filter(condition)

  /**
   * Filters rows using the given SQL expression.
   * {{{
   *   peopleDs.where("age > 15")
   * }}}
   *
   * @group typedrel
   * @since 1.6.0
   */
  def where(conditionExpr: String): Dataset[T] = {
    filter(Column(sqlContext.sessionState.sqlParser.parseExpression(conditionExpr)))
  }

  /**
   * Groups the [[Dataset]] using the specified columns, so we can run aggregation on them.  See
   * [[RelationalGroupedDataset]] for all the available aggregate functions.
   *
   * {{{
   *   // Compute the average for all numeric columns grouped by department.
   *   ds.groupBy($"department").avg()
   *
   *   // Compute the max age and average salary, grouped by department and gender.
   *   ds.groupBy($"department", $"gender").agg(Map(
   *     "salary" -> "avg",
   *     "age" -> "max"
   *   ))
   * }}}
   *
   * @group untypedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def groupBy(cols: Column*): RelationalGroupedDataset = {
    RelationalGroupedDataset(toDF(), cols.map(_.expr), RelationalGroupedDataset.GroupByType)
  }

  /**
   * Create a multi-dimensional rollup for the current [[Dataset]] using the specified columns,
   * so we can run aggregation on them.
   * See [[RelationalGroupedDataset]] for all the available aggregate functions.
   *
   * {{{
   *   // Compute the average for all numeric columns rolluped by department and group.
   *   ds.rollup($"department", $"group").avg()
   *
   *   // Compute the max age and average salary, rolluped by department and gender.
   *   ds.rollup($"department", $"gender").agg(Map(
   *     "salary" -> "avg",
   *     "age" -> "max"
   *   ))
   * }}}
   *
   * @group untypedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def rollup(cols: Column*): RelationalGroupedDataset = {
    RelationalGroupedDataset(toDF(), cols.map(_.expr), RelationalGroupedDataset.RollupType)
  }

  /**
   * Create a multi-dimensional cube for the current [[Dataset]] using the specified columns,
   * so we can run aggregation on them.
   * See [[RelationalGroupedDataset]] for all the available aggregate functions.
   *
   * {{{
   *   // Compute the average for all numeric columns cubed by department and group.
   *   ds.cube($"department", $"group").avg()
   *
   *   // Compute the max age and average salary, cubed by department and gender.
   *   ds.cube($"department", $"gender").agg(Map(
   *     "salary" -> "avg",
   *     "age" -> "max"
   *   ))
   * }}}
   *
   * @group untypedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def cube(cols: Column*): RelationalGroupedDataset = {
    RelationalGroupedDataset(toDF(), cols.map(_.expr), RelationalGroupedDataset.CubeType)
  }

  /**
   * Groups the [[Dataset]] using the specified columns, so that we can run aggregation on them.
   * See [[RelationalGroupedDataset]] for all the available aggregate functions.
   *
   * This is a variant of groupBy that can only group by existing columns using column names
   * (i.e. cannot construct expressions).
   *
   * {{{
   *   // Compute the average for all numeric columns grouped by department.
   *   ds.groupBy("department").avg()
   *
   *   // Compute the max age and average salary, grouped by department and gender.
   *   ds.groupBy($"department", $"gender").agg(Map(
   *     "salary" -> "avg",
   *     "age" -> "max"
   *   ))
   * }}}
   * @group untypedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def groupBy(col1: String, cols: String*): RelationalGroupedDataset = {
    val colNames: Seq[String] = col1 +: cols
    RelationalGroupedDataset(
      toDF(), colNames.map(colName => resolve(colName)), RelationalGroupedDataset.GroupByType)
  }

  /**
   * :: Experimental ::
   * (Scala-specific)
   * Reduces the elements of this [[Dataset]] using the specified binary function. The given `func`
   * must be commutative and associative or the result may be non-deterministic.
   *
   * @group action
   * @since 1.6.0
   */
  @Experimental
  def reduce(func: (T, T) => T): T = rdd.reduce(func)

  /**
   * :: Experimental ::
   * (Java-specific)
   * Reduces the elements of this Dataset using the specified binary function.  The given `func`
   * must be commutative and associative or the result may be non-deterministic.
   *
   * @group action
   * @since 1.6.0
   */
  @Experimental
  def reduce(func: ReduceFunction[T]): T = reduce(func.call(_, _))

  /**
   * :: Experimental ::
   * (Scala-specific)
   * Returns a [[KeyValueGroupedDataset]] where the data is grouped by the given key `func`.
   *
   * @group typedrel
   * @since 2.0.0
   */
  @Experimental
  def groupByKey[K: Encoder](func: T => K): KeyValueGroupedDataset[K, T] = {
    val inputPlan = logicalPlan
    val withGroupingKey = AppendColumns(func, inputPlan)
    val executed = sqlContext.executePlan(withGroupingKey)

    new KeyValueGroupedDataset(
      encoderFor[K],
      encoderFor[T],
      executed,
      inputPlan.output,
      withGroupingKey.newColumns)
  }

  /**
   * :: Experimental ::
   * Returns a [[KeyValueGroupedDataset]] where the data is grouped by the given [[Column]]
   * expressions.
   *
   * @group typedrel
   * @since 2.0.0
   */
  @Experimental
  @scala.annotation.varargs
  def groupByKey(cols: Column*): KeyValueGroupedDataset[Row, T] = {
    val withKeyColumns = logicalPlan.output ++ cols.map(_.expr).map(UnresolvedAlias(_))
    val withKey = Project(withKeyColumns, logicalPlan)
    val executed = sqlContext.executePlan(withKey)

    val dataAttributes = executed.analyzed.output.dropRight(cols.size)
    val keyAttributes = executed.analyzed.output.takeRight(cols.size)

    new KeyValueGroupedDataset(
      RowEncoder(keyAttributes.toStructType),
      encoderFor[T],
      executed,
      dataAttributes,
      keyAttributes)
  }

  /**
   * :: Experimental ::
   * (Java-specific)
   * Returns a [[KeyValueGroupedDataset]] where the data is grouped by the given key `func`.
   *
   * @group typedrel
   * @since 2.0.0
   */
  @Experimental
  def groupByKey[K](func: MapFunction[T, K], encoder: Encoder[K]): KeyValueGroupedDataset[K, T] =
    groupByKey(func.call(_))(encoder)

  /**
   * Create a multi-dimensional rollup for the current [[Dataset]] using the specified columns,
   * so we can run aggregation on them.
   * See [[RelationalGroupedDataset]] for all the available aggregate functions.
   *
   * This is a variant of rollup that can only group by existing columns using column names
   * (i.e. cannot construct expressions).
   *
   * {{{
   *   // Compute the average for all numeric columns rolluped by department and group.
   *   ds.rollup("department", "group").avg()
   *
   *   // Compute the max age and average salary, rolluped by department and gender.
   *   ds.rollup($"department", $"gender").agg(Map(
   *     "salary" -> "avg",
   *     "age" -> "max"
   *   ))
   * }}}
   *
   * @group untypedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def rollup(col1: String, cols: String*): RelationalGroupedDataset = {
    val colNames: Seq[String] = col1 +: cols
    RelationalGroupedDataset(
      toDF(), colNames.map(colName => resolve(colName)), RelationalGroupedDataset.RollupType)
  }

  /**
   * Create a multi-dimensional cube for the current [[Dataset]] using the specified columns,
   * so we can run aggregation on them.
   * See [[RelationalGroupedDataset]] for all the available aggregate functions.
   *
   * This is a variant of cube that can only group by existing columns using column names
   * (i.e. cannot construct expressions).
   *
   * {{{
   *   // Compute the average for all numeric columns cubed by department and group.
   *   ds.cube("department", "group").avg()
   *
   *   // Compute the max age and average salary, cubed by department and gender.
   *   ds.cube($"department", $"gender").agg(Map(
   *     "salary" -> "avg",
   *     "age" -> "max"
   *   ))
   * }}}
   * @group untypedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def cube(col1: String, cols: String*): RelationalGroupedDataset = {
    val colNames: Seq[String] = col1 +: cols
    RelationalGroupedDataset(
      toDF(), colNames.map(colName => resolve(colName)), RelationalGroupedDataset.CubeType)
  }

  /**
   * (Scala-specific) Aggregates on the entire [[Dataset]] without groups.
   * {{{
   *   // ds.agg(...) is a shorthand for ds.groupBy().agg(...)
   *   ds.agg("age" -> "max", "salary" -> "avg")
   *   ds.groupBy().agg("age" -> "max", "salary" -> "avg")
   * }}}
   *
   * @group untypedrel
   * @since 2.0.0
   */
  def agg(aggExpr: (String, String), aggExprs: (String, String)*): DataFrame = {
    groupBy().agg(aggExpr, aggExprs : _*)
  }

  /**
   * (Scala-specific) Aggregates on the entire [[Dataset]] without groups.
   * {{{
   *   // ds.agg(...) is a shorthand for ds.groupBy().agg(...)
   *   ds.agg(Map("age" -> "max", "salary" -> "avg"))
   *   ds.groupBy().agg(Map("age" -> "max", "salary" -> "avg"))
   * }}}
   *
   * @group untypedrel
   * @since 2.0.0
   */
  def agg(exprs: Map[String, String]): DataFrame = groupBy().agg(exprs)

  /**
   * (Java-specific) Aggregates on the entire [[Dataset]] without groups.
   * {{{
   *   // ds.agg(...) is a shorthand for ds.groupBy().agg(...)
   *   ds.agg(Map("age" -> "max", "salary" -> "avg"))
   *   ds.groupBy().agg(Map("age" -> "max", "salary" -> "avg"))
   * }}}
   *
   * @group untypedrel
   * @since 2.0.0
   */
  def agg(exprs: java.util.Map[String, String]): DataFrame = groupBy().agg(exprs)

  /**
   * Aggregates on the entire [[Dataset]] without groups.
   * {{{
   *   // ds.agg(...) is a shorthand for ds.groupBy().agg(...)
   *   ds.agg(max($"age"), avg($"salary"))
   *   ds.groupBy().agg(max($"age"), avg($"salary"))
   * }}}
   *
   * @group untypedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def agg(expr: Column, exprs: Column*): DataFrame = groupBy().agg(expr, exprs : _*)

  /**
   * Returns a new [[Dataset]] by taking the first `n` rows. The difference between this function
   * and `head` is that `head` is an action and returns an array (by triggering query execution)
   * while `limit` returns a new [[Dataset]].
   *
   * @group typedrel
   * @since 2.0.0
   */
  def limit(n: Int): Dataset[T] = withTypedPlan {
    Limit(Literal(n), logicalPlan)
  }

  /**
   * Returns a new [[Dataset]] containing union of rows in this Dataset and another Dataset.
   * This is equivalent to `UNION ALL` in SQL.
   *
   * To do a SQL-style set union (that does deduplication of elements), use this function followed
   * by a [[distinct]].
   *
   * @group typedrel
   * @since 2.0.0
   */
  def unionAll(other: Dataset[T]): Dataset[T] = withTypedPlan {
    // This breaks caching, but it's usually ok because it addresses a very specific use case:
    // using union to union many files or partitions.
    CombineUnions(Union(logicalPlan, other.logicalPlan))
  }

  /**
   * Returns a new [[Dataset]] containing union of rows in this Dataset and another Dataset.
   * This is equivalent to `UNION ALL` in SQL.
   *
   * @group typedrel
   * @since 2.0.0
   */
  def union(other: Dataset[T]): Dataset[T] = unionAll(other)

  /**
   * Returns a new [[Dataset]] containing rows only in both this Dataset and another Dataset.
   * This is equivalent to `INTERSECT` in SQL.
   *
   * Note that, equality checking is performed directly on the encoded representation of the data
   * and thus is not affected by a custom `equals` function defined on `T`.
   *
   * @group typedrel
   * @since 1.6.0
   */
  def intersect(other: Dataset[T]): Dataset[T] = withTypedPlan {
    Intersect(logicalPlan, other.logicalPlan)
  }

  /**
   * Returns a new [[Dataset]] containing rows in this Dataset but not in another Dataset.
   * This is equivalent to `EXCEPT` in SQL.
   *
   * Note that, equality checking is performed directly on the encoded representation of the data
   * and thus is not affected by a custom `equals` function defined on `T`.
   *
   * @group typedrel
   * @since 2.0.0
   */
  def except(other: Dataset[T]): Dataset[T] = withTypedPlan {
    Except(logicalPlan, other.logicalPlan)
  }

  /**
   * Returns a new [[Dataset]] containing rows in this Dataset but not in another Dataset.
   * This is equivalent to `EXCEPT` in SQL.
   *
   * Note that, equality checking is performed directly on the encoded representation of the data
   * and thus is not affected by a custom `equals` function defined on `T`.
   *
   * @group typedrel
   * @since 2.0.0
   */
  def subtract(other: Dataset[T]): Dataset[T] = except(other)

  /**
   * Returns a new [[Dataset]] by sampling a fraction of rows.
   *
   * @param withReplacement Sample with replacement or not.
   * @param fraction Fraction of rows to generate.
   * @param seed Seed for sampling.
   *
   * @group typedrel
   * @since 1.6.0
   */
  def sample(withReplacement: Boolean, fraction: Double, seed: Long): Dataset[T] = withTypedPlan {
    Sample(0.0, fraction, withReplacement, seed, logicalPlan)()
  }

  /**
   * Returns a new [[Dataset]] by sampling a fraction of rows, using a random seed.
   *
   * @param withReplacement Sample with replacement or not.
   * @param fraction Fraction of rows to generate.
   *
   * @group typedrel
   * @since 1.6.0
   */
  def sample(withReplacement: Boolean, fraction: Double): Dataset[T] = {
    sample(withReplacement, fraction, Utils.random.nextLong)
  }

  /**
   * Randomly splits this [[Dataset]] with the provided weights.
   *
   * @param weights weights for splits, will be normalized if they don't sum to 1.
   * @param seed Seed for sampling.
   *
   * @group typedrel
   * @since 2.0.0
   */
  def randomSplit(weights: Array[Double], seed: Long): Array[Dataset[T]] = {
    // It is possible that the underlying dataframe doesn't guarantee the ordering of rows in its
    // constituent partitions each time a split is materialized which could result in
    // overlapping splits. To prevent this, we explicitly sort each input partition to make the
    // ordering deterministic.
    val sorted = Sort(logicalPlan.output.map(SortOrder(_, Ascending)), global = false, logicalPlan)
    val sum = weights.sum
    val normalizedCumWeights = weights.map(_ / sum).scanLeft(0.0d)(_ + _)
    normalizedCumWeights.sliding(2).map { x =>
      new Dataset[T](
        sqlContext, Sample(x(0), x(1), withReplacement = false, seed, sorted)(), encoder)
    }.toArray
  }

  /**
   * Randomly splits this [[Dataset]] with the provided weights.
   *
   * @param weights weights for splits, will be normalized if they don't sum to 1.
   * @group typedrel
   * @since 2.0.0
   */
  def randomSplit(weights: Array[Double]): Array[Dataset[T]] = {
    randomSplit(weights, Utils.random.nextLong)
  }

  /**
   * Randomly splits this [[Dataset]] with the provided weights. Provided for the Python Api.
   *
   * @param weights weights for splits, will be normalized if they don't sum to 1.
   * @param seed Seed for sampling.
   */
  private[spark] def randomSplit(weights: List[Double], seed: Long): Array[Dataset[T]] = {
    randomSplit(weights.toArray, seed)
  }

  /**
   * :: Experimental ::
   * (Scala-specific) Returns a new [[Dataset]] where each row has been expanded to zero or more
   * rows by the provided function.  This is similar to a `LATERAL VIEW` in HiveQL. The columns of
   * the input row are implicitly joined with each row that is output by the function.
   *
   * The following example uses this function to count the number of books which contain
   * a given word:
   *
   * {{{
   *   case class Book(title: String, words: String)
   *   val ds: Dataset[Book]
   *
   *   case class Word(word: String)
   *   val allWords = ds.explode('words) {
   *     case Row(words: String) => words.split(" ").map(Word(_))
   *   }
   *
   *   val bookCountPerWord = allWords.groupBy("word").agg(countDistinct("title"))
   * }}}
   *
   * @group untypedrel
   * @since 2.0.0
   */
  @Experimental
  def explode[A <: Product : TypeTag](input: Column*)(f: Row => TraversableOnce[A]): DataFrame = {
    val schema = ScalaReflection.schemaFor[A].dataType.asInstanceOf[StructType]

    val elementTypes = schema.toAttributes.map {
      attr => (attr.dataType, attr.nullable, attr.name) }
    val names = schema.toAttributes.map(_.name)
    val convert = CatalystTypeConverters.createToCatalystConverter(schema)

    val rowFunction =
      f.andThen(_.map(convert(_).asInstanceOf[InternalRow]))
    val generator = UserDefinedGenerator(elementTypes, rowFunction, input.map(_.expr))

    withPlan {
      Generate(generator, join = true, outer = false,
        qualifier = None, generatorOutput = Nil, logicalPlan)
    }
  }

  /**
   * :: Experimental ::
   * (Scala-specific) Returns a new [[Dataset]] where a single column has been expanded to zero
   * or more rows by the provided function.  This is similar to a `LATERAL VIEW` in HiveQL. All
   * columns of the input row are implicitly joined with each value that is output by the function.
   *
   * {{{
   *   ds.explode("words", "word") {words: String => words.split(" ")}
   * }}}
   *
   * @group untypedrel
   * @since 2.0.0
   */
  @Experimental
  def explode[A, B : TypeTag](inputColumn: String, outputColumn: String)(f: A => TraversableOnce[B])
    : DataFrame = {
    val dataType = ScalaReflection.schemaFor[B].dataType
    val attributes = AttributeReference(outputColumn, dataType)() :: Nil
    // TODO handle the metadata?
    val elementTypes = attributes.map { attr => (attr.dataType, attr.nullable, attr.name) }

    def rowFunction(row: Row): TraversableOnce[InternalRow] = {
      val convert = CatalystTypeConverters.createToCatalystConverter(dataType)
      f(row(0).asInstanceOf[A]).map(o => InternalRow(convert(o)))
    }
    val generator = UserDefinedGenerator(elementTypes, rowFunction, apply(inputColumn).expr :: Nil)

    withPlan {
      Generate(generator, join = true, outer = false,
        qualifier = None, generatorOutput = Nil, logicalPlan)
    }
  }

  /**
   * Returns a new [[Dataset]] by adding a column or replacing the existing column that has
   * the same name.
   *
   * @group untypedrel
   * @since 2.0.0
   */
  def withColumn(colName: String, col: Column): DataFrame = {
    val resolver = sqlContext.sessionState.analyzer.resolver
    val output = queryExecution.analyzed.output
    val shouldReplace = output.exists(f => resolver(f.name, colName))
    if (shouldReplace) {
      val columns = output.map { field =>
        if (resolver(field.name, colName)) {
          col.as(colName)
        } else {
          Column(field)
        }
      }
      select(columns : _*)
    } else {
      select(Column("*"), col.as(colName))
    }
  }

  /**
   * Returns a new [[Dataset]] by adding a column with metadata.
   */
  private[spark] def withColumn(colName: String, col: Column, metadata: Metadata): DataFrame = {
    val resolver = sqlContext.sessionState.analyzer.resolver
    val output = queryExecution.analyzed.output
    val shouldReplace = output.exists(f => resolver(f.name, colName))
    if (shouldReplace) {
      val columns = output.map { field =>
        if (resolver(field.name, colName)) {
          col.as(colName, metadata)
        } else {
          Column(field)
        }
      }
      select(columns : _*)
    } else {
      select(Column("*"), col.as(colName, metadata))
    }
  }

  /**
   * Returns a new [[Dataset]] with a column renamed.
   * This is a no-op if schema doesn't contain existingName.
   *
   * @group untypedrel
   * @since 2.0.0
   */
  def withColumnRenamed(existingName: String, newName: String): DataFrame = {
    val resolver = sqlContext.sessionState.analyzer.resolver
    val output = queryExecution.analyzed.output
    val shouldRename = output.exists(f => resolver(f.name, existingName))
    if (shouldRename) {
      val columns = output.map { col =>
        if (resolver(col.name, existingName)) {
          Column(col).as(newName)
        } else {
          Column(col)
        }
      }
      select(columns : _*)
    } else {
      toDF()
    }
  }

  /**
   * Returns a new [[Dataset]] with a column dropped.
   * This is a no-op if schema doesn't contain column name.
   *
   * @group untypedrel
   * @since 2.0.0
   */
  def drop(colName: String): DataFrame = {
    drop(Seq(colName) : _*)
  }

  /**
   * Returns a new [[Dataset]] with columns dropped.
   * This is a no-op if schema doesn't contain column name(s).
   *
   * @group untypedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def drop(colNames: String*): DataFrame = {
    val resolver = sqlContext.sessionState.analyzer.resolver
    val remainingCols =
      schema.filter(f => colNames.forall(n => !resolver(f.name, n))).map(f => Column(f.name))
    if (remainingCols.size == this.schema.size) {
      toDF()
    } else {
      this.select(remainingCols: _*)
    }
  }

  /**
   * Returns a new [[Dataset]] with a column dropped.
   * This version of drop accepts a Column rather than a name.
   * This is a no-op if the Datasetdoesn't have a column
   * with an equivalent expression.
   *
   * @group untypedrel
   * @since 2.0.0
   */
  def drop(col: Column): DataFrame = {
    val expression = col match {
      case Column(u: UnresolvedAttribute) =>
        queryExecution.analyzed.resolveQuoted(
          u.name, sqlContext.sessionState.analyzer.resolver).getOrElse(u)
      case Column(expr: Expression) => expr
    }
    val attrs = this.logicalPlan.output
    val colsAfterDrop = attrs.filter { attr =>
      attr != expression
    }.map(attr => Column(attr))
    select(colsAfterDrop : _*)
  }

  /**
   * Returns a new [[Dataset]] that contains only the unique rows from this [[Dataset]].
   * This is an alias for `distinct`.
   *
   * @group typedrel
   * @since 2.0.0
   */
  def dropDuplicates(): Dataset[T] = dropDuplicates(this.columns)

  /**
   * (Scala-specific) Returns a new [[Dataset]] with duplicate rows removed, considering only
   * the subset of columns.
   *
   * @group typedrel
   * @since 2.0.0
   */
  def dropDuplicates(colNames: Seq[String]): Dataset[T] = withTypedPlan {
    val groupCols = colNames.map(resolve)
    val groupColExprIds = groupCols.map(_.exprId)
    val aggCols = logicalPlan.output.map { attr =>
      if (groupColExprIds.contains(attr.exprId)) {
        attr
      } else {
        Alias(new First(attr).toAggregateExpression(), attr.name)()
      }
    }
    Aggregate(groupCols, aggCols, logicalPlan)
  }

  /**
   * Returns a new [[Dataset]] with duplicate rows removed, considering only
   * the subset of columns.
   *
   * @group typedrel
   * @since 2.0.0
   */
  def dropDuplicates(colNames: Array[String]): Dataset[T] = dropDuplicates(colNames.toSeq)

  /**
   * Computes statistics for numeric columns, including count, mean, stddev, min, and max.
   * If no columns are given, this function computes statistics for all numerical columns.
   *
   * This function is meant for exploratory data analysis, as we make no guarantee about the
   * backward compatibility of the schema of the resulting [[Dataset]]. If you want to
   * programmatically compute summary statistics, use the `agg` function instead.
   *
   * {{{
   *   ds.describe("age", "height").show()
   *
   *   // output:
   *   // summary age   height
   *   // count   10.0  10.0
   *   // mean    53.3  178.05
   *   // stddev  11.6  15.7
   *   // min     18.0  163.0
   *   // max     92.0  192.0
   * }}}
   *
   * @group action
   * @since 1.6.0
   */
  @scala.annotation.varargs
  def describe(cols: String*): DataFrame = withPlan {

    // The list of summary statistics to compute, in the form of expressions.
    val statistics = List[(String, Expression => Expression)](
      "count" -> ((child: Expression) => Count(child).toAggregateExpression()),
      "mean" -> ((child: Expression) => Average(child).toAggregateExpression()),
      "stddev" -> ((child: Expression) => StddevSamp(child).toAggregateExpression()),
      "min" -> ((child: Expression) => Min(child).toAggregateExpression()),
      "max" -> ((child: Expression) => Max(child).toAggregateExpression()))

    val outputCols =
      (if (cols.isEmpty) numericColumns.map(usePrettyExpression(_).sql) else cols).toList

    val ret: Seq[Row] = if (outputCols.nonEmpty) {
      val aggExprs = statistics.flatMap { case (_, colToAgg) =>
        outputCols.map(c => Column(Cast(colToAgg(Column(c).expr), StringType)).as(c))
      }

      val row = agg(aggExprs.head, aggExprs.tail: _*).head().toSeq

      // Pivot the data so each summary is one row
      row.grouped(outputCols.size).toSeq.zip(statistics).map { case (aggregation, (statistic, _)) =>
        Row(statistic :: aggregation.toList: _*)
      }
    } else {
      // If there are no output columns, just output a single column that contains the stats.
      statistics.map { case (name, _) => Row(name) }
    }

    // All columns are string type
    val schema = StructType(
      StructField("summary", StringType) :: outputCols.map(StructField(_, StringType))).toAttributes
    LocalRelation.fromExternalRows(schema, ret)
  }

  /**
   * Returns the first `n` rows.
   *
   * @note this method should only be used if the resulting array is expected to be small, as
   * all the data is loaded into the driver's memory.
   *
   * @group action
   * @since 1.6.0
   */
  def head(n: Int): Array[T] = withTypedCallback("head", limit(n)) { df =>
    df.collect(needCallback = false)
  }

  /**
   * Returns the first row.
   * @group action
   * @since 1.6.0
   */
  def head(): T = head(1).head

  /**
   * Returns the first row. Alias for head().
   * @group action
   * @since 1.6.0
   */
  def first(): T = head()

  /**
   * Concise syntax for chaining custom transformations.
   * {{{
   *   def featurize(ds: Dataset[T]): Dataset[U] = ...
   *
   *   ds
   *     .transform(featurize)
   *     .transform(...)
   * }}}
   *
   * @group func
   * @since 1.6.0
   */
  def transform[U](t: Dataset[T] => Dataset[U]): Dataset[U] = t(this)

  /**
   * :: Experimental ::
   * (Scala-specific)
   * Returns a new [[Dataset]] that only contains elements where `func` returns `true`.
   *
   * @group func
   * @since 1.6.0
   */
  @Experimental
  def filter(func: T => Boolean): Dataset[T] = mapPartitions(_.filter(func))

  /**
   * :: Experimental ::
   * (Java-specific)
   * Returns a new [[Dataset]] that only contains elements where `func` returns `true`.
   *
   * @group func
   * @since 1.6.0
   */
  @Experimental
  def filter(func: FilterFunction[T]): Dataset[T] = filter(t => func.call(t))

  /**
   * :: Experimental ::
   * (Scala-specific)
   * Returns a new [[Dataset]] that contains the result of applying `func` to each element.
   *
   * @group func
   * @since 1.6.0
   */
  @Experimental
  def map[U : Encoder](func: T => U): Dataset[U] = mapPartitions(_.map(func))

  /**
   * :: Experimental ::
   * (Java-specific)
   * Returns a new [[Dataset]] that contains the result of applying `func` to each element.
   *
   * @group func
   * @since 1.6.0
   */
  @Experimental
  def map[U](func: MapFunction[T, U], encoder: Encoder[U]): Dataset[U] =
    map(t => func.call(t))(encoder)

  /**
   * :: Experimental ::
   * (Scala-specific)
   * Returns a new [[Dataset]] that contains the result of applying `func` to each partition.
   *
   * @group func
   * @since 1.6.0
   */
  @Experimental
  def mapPartitions[U : Encoder](func: Iterator[T] => Iterator[U]): Dataset[U] = {
    new Dataset[U](
      sqlContext,
      MapPartitions[T, U](func, logicalPlan),
      implicitly[Encoder[U]])
  }

  /**
   * :: Experimental ::
   * (Java-specific)
   * Returns a new [[Dataset]] that contains the result of applying `func` to each partition.
   *
   * @group func
   * @since 1.6.0
   */
  @Experimental
  def mapPartitions[U](f: MapPartitionsFunction[T, U], encoder: Encoder[U]): Dataset[U] = {
    val func: (Iterator[T]) => Iterator[U] = x => f.call(x.asJava).asScala
    mapPartitions(func)(encoder)
  }

  /**
   * :: Experimental ::
   * (Scala-specific)
   * Returns a new [[Dataset]] by first applying a function to all elements of this [[Dataset]],
   * and then flattening the results.
   *
   * @group func
   * @since 1.6.0
   */
  @Experimental
  def flatMap[U : Encoder](func: T => TraversableOnce[U]): Dataset[U] =
    mapPartitions(_.flatMap(func))

  /**
   * :: Experimental ::
   * (Java-specific)
   * Returns a new [[Dataset]] by first applying a function to all elements of this [[Dataset]],
   * and then flattening the results.
   *
   * @group func
   * @since 1.6.0
   */
  @Experimental
  def flatMap[U](f: FlatMapFunction[T, U], encoder: Encoder[U]): Dataset[U] = {
    val func: (T) => Iterator[U] = x => f.call(x).asScala
    flatMap(func)(encoder)
  }

  /**
   * Applies a function `f` to all rows.
   *
   * @group action
   * @since 1.6.0
   */
  def foreach(f: T => Unit): Unit = withNewExecutionId {
    rdd.foreach(f)
  }

  /**
   * (Java-specific)
   * Runs `func` on each element of this [[Dataset]].
   *
   * @group action
   * @since 1.6.0
   */
  def foreach(func: ForeachFunction[T]): Unit = foreach(func.call(_))

  /**
   * Applies a function f to each partition of this [[Dataset]].
   *
   * @group action
   * @since 1.6.0
   */
  def foreachPartition(f: Iterator[T] => Unit): Unit = withNewExecutionId {
    rdd.foreachPartition(f)
  }

  /**
   * (Java-specific)
   * Runs `func` on each partition of this [[Dataset]].
   *
   * @group action
   * @since 1.6.0
   */
  def foreachPartition(func: ForeachPartitionFunction[T]): Unit =
    foreachPartition(it => func.call(it.asJava))

  /**
   * Returns the first `n` rows in the [[Dataset]].
   *
   * Running take requires moving data into the application's driver process, and doing so with
   * a very large `n` can crash the driver process with OutOfMemoryError.
   *
   * @group action
   * @since 1.6.0
   */
  def take(n: Int): Array[T] = head(n)

  /**
   * Returns the first `n` rows in the [[Dataset]] as a list.
   *
   * Running take requires moving data into the application's driver process, and doing so with
   * a very large `n` can crash the driver process with OutOfMemoryError.
   *
   * @group action
   * @since 1.6.0
   */
  def takeAsList(n: Int): java.util.List[T] = java.util.Arrays.asList(take(n) : _*)

  /**
   * Returns an array that contains all of [[Row]]s in this [[Dataset]].
   *
   * Running collect requires moving all the data into the application's driver process, and
   * doing so on a very large dataset can crash the driver process with OutOfMemoryError.
   *
   * For Java API, use [[collectAsList]].
   *
   * @group action
   * @since 1.6.0
   */
  def collect(): Array[T] = collect(needCallback = true)

  /**
   * Returns a Java list that contains all of [[Row]]s in this [[Dataset]].
   *
   * Running collect requires moving all the data into the application's driver process, and
   * doing so on a very large dataset can crash the driver process with OutOfMemoryError.
   *
   * @group action
   * @since 1.6.0
   */
  def collectAsList(): java.util.List[T] = withCallback("collectAsList", toDF()) { _ =>
    withNewExecutionId {
      val values = queryExecution.executedPlan.executeCollect().map(boundTEncoder.fromRow)
      java.util.Arrays.asList(values : _*)
    }
  }

  private def collect(needCallback: Boolean): Array[T] = {
    def execute(): Array[T] = withNewExecutionId {
      queryExecution.executedPlan.executeCollect().map(boundTEncoder.fromRow)
    }

    if (needCallback) {
      withCallback("collect", toDF())(_ => execute())
    } else {
      execute()
    }
  }

  /**
   * Returns the number of rows in the [[Dataset]].
   * @group action
   * @since 1.6.0
   */
  def count(): Long = withCallback("count", groupBy().count()) { df =>
    df.collect(needCallback = false).head.getLong(0)
  }

  /**
   * Returns a new [[Dataset]] that has exactly `numPartitions` partitions.
   *
   * @group typedrel
   * @since 1.6.0
   */
  def repartition(numPartitions: Int): Dataset[T] = withTypedPlan {
    Repartition(numPartitions, shuffle = true, logicalPlan)
  }

  /**
   * Returns a new [[Dataset]] partitioned by the given partitioning expressions into
   * `numPartitions`. The resulting Datasetis hash partitioned.
   *
   * This is the same operation as "DISTRIBUTE BY" in SQL (Hive QL).
   *
   * @group typedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def repartition(numPartitions: Int, partitionExprs: Column*): Dataset[T] = withTypedPlan {
    RepartitionByExpression(partitionExprs.map(_.expr), logicalPlan, Some(numPartitions))
  }

  /**
   * Returns a new [[Dataset]] partitioned by the given partitioning expressions preserving
   * the existing number of partitions. The resulting Datasetis hash partitioned.
   *
   * This is the same operation as "DISTRIBUTE BY" in SQL (Hive QL).
   *
   * @group typedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def repartition(partitionExprs: Column*): Dataset[T] = withTypedPlan {
    RepartitionByExpression(partitionExprs.map(_.expr), logicalPlan, numPartitions = None)
  }

  /**
   * Returns a new [[Dataset]] that has exactly `numPartitions` partitions.
   * Similar to coalesce defined on an [[RDD]], this operation results in a narrow dependency, e.g.
   * if you go from 1000 partitions to 100 partitions, there will not be a shuffle, instead each of
   * the 100 new partitions will claim 10 of the current partitions.
   *
   * @group rdd
   * @since 1.6.0
   */
  def coalesce(numPartitions: Int): Dataset[T] = withTypedPlan {
    Repartition(numPartitions, shuffle = false, logicalPlan)
  }

  /**
   * Returns a new [[Dataset]] that contains only the unique rows from this [[Dataset]].
   * This is an alias for `dropDuplicates`.
   *
   * Note that, equality checking is performed directly on the encoded representation of the data
   * and thus is not affected by a custom `equals` function defined on `T`.
   *
   * @group typedrel
   * @since 2.0.0
   */
  def distinct(): Dataset[T] = dropDuplicates()

  /**
   * Persist this [[Dataset]] with the default storage level (`MEMORY_AND_DISK`).
   *
   * @group basic
   * @since 1.6.0
   */
  def persist(): this.type = {
    sqlContext.cacheManager.cacheQuery(this)
    this
  }

  /**
   * Persist this [[Dataset]] with the default storage level (`MEMORY_AND_DISK`).
   *
   * @group basic
   * @since 1.6.0
   */
  def cache(): this.type = persist()

  /**
   * Persist this [[Dataset]] with the given storage level.
   * @param newLevel One of: `MEMORY_ONLY`, `MEMORY_AND_DISK`, `MEMORY_ONLY_SER`,
   *                 `MEMORY_AND_DISK_SER`, `DISK_ONLY`, `MEMORY_ONLY_2`,
   *                 `MEMORY_AND_DISK_2`, etc.
   *
   * @group basic
   * @since 1.6.0
   */
  def persist(newLevel: StorageLevel): this.type = {
    sqlContext.cacheManager.cacheQuery(this, None, newLevel)
    this
  }

  /**
   * Mark the [[Dataset]] as non-persistent, and remove all blocks for it from memory and disk.
   *
   * @param blocking Whether to block until all blocks are deleted.
   *
   * @group basic
   * @since 1.6.0
   */
  def unpersist(blocking: Boolean): this.type = {
    sqlContext.cacheManager.tryUncacheQuery(this, blocking)
    this
  }

  /**
   * Mark the [[Dataset]] as non-persistent, and remove all blocks for it from memory and disk.
   *
   * @group basic
   * @since 1.6.0
   */
  def unpersist(): this.type = unpersist(blocking = false)

  /**
   * Represents the content of the [[Dataset]] as an [[RDD]] of [[Row]]s. Note that the RDD is
   * memoized. Once called, it won't change even if you change any query planning related Spark SQL
   * configurations (e.g. `spark.sql.shuffle.partitions`).
   *
   * @group rdd
   * @since 1.6.0
   */
  lazy val rdd: RDD[T] = {
    queryExecution.toRdd.mapPartitions { rows =>
      rows.map(boundTEncoder.fromRow)
    }
  }

  /**
   * Returns the content of the [[Dataset]] as a [[JavaRDD]] of [[Row]]s.
   * @group rdd
   * @since 1.6.0
   */
  def toJavaRDD: JavaRDD[T] = rdd.toJavaRDD()

  /**
   * Returns the content of the [[Dataset]] as a [[JavaRDD]] of [[Row]]s.
   * @group rdd
   * @since 1.6.0
   */
  def javaRDD: JavaRDD[T] = toJavaRDD

  /**
   * Registers this [[Dataset]] as a temporary table using the given name.  The lifetime of this
   * temporary table is tied to the [[SQLContext]] that was used to create this Dataset.
   *
   * @group basic
   * @since 1.6.0
   */
  def registerTempTable(tableName: String): Unit = {
    sqlContext.registerDataFrameAsTable(toDF(), tableName)
  }

  /**
   * :: Experimental ::
   * Interface for saving the content of the [[Dataset]] out into external storage or streams.
   *
   * @group output
   * @since 1.6.0
   */
  @Experimental
  def write: DataFrameWriter = new DataFrameWriter(toDF())

  /**
   * Returns the content of the [[Dataset]] as a Dataset of JSON strings.
   * @since 2.0.0
   */
  def toJSON: Dataset[String] = {
    val rowSchema = this.schema
    val rdd: RDD[String] = queryExecution.toRdd.mapPartitions { iter =>
      val writer = new CharArrayWriter()
      // create the Generator without separator inserted between 2 records
      val gen = new JsonFactory().createGenerator(writer).setRootValueSeparator(null)

      new Iterator[String] {
        override def hasNext: Boolean = iter.hasNext
        override def next(): String = {
          JacksonGenerator(rowSchema, gen)(iter.next())
          gen.flush()

          val json = writer.toString
          if (hasNext) {
            writer.reset()
          } else {
            gen.close()
          }

          json
        }
      }
    }
    import sqlContext.implicits.newStringEncoder
    sqlContext.createDataset(rdd)
  }

  /**
   * Returns a best-effort snapshot of the files that compose this Dataset. This method simply
   * asks each constituent BaseRelation for its respective files and takes the union of all results.
   * Depending on the source relations, this may not find all input files. Duplicates are removed.
   *
   * @group basic
   * @since 2.0.0
   */
  def inputFiles: Array[String] = {
    val files: Seq[String] = logicalPlan.collect {
      case LogicalRelation(fsBasedRelation: FileRelation, _, _) =>
        fsBasedRelation.inputFiles
      case fr: FileRelation =>
        fr.inputFiles
    }.flatten
    files.toSet.toArray
  }

  ////////////////////////////////////////////////////////////////////////////
  // For Python API
  ////////////////////////////////////////////////////////////////////////////

  /**
   * Converts a JavaRDD to a PythonRDD.
   */
  protected[sql] def javaToPython: JavaRDD[Array[Byte]] = {
    val structType = schema  // capture it for closure
    val rdd = queryExecution.toRdd.map(EvaluatePython.toJava(_, structType))
    EvaluatePython.javaToPython(rdd)
  }

  protected[sql] def collectToPython(): Int = {
    withNewExecutionId {
      PythonRDD.collectAndServe(javaToPython.rdd)
    }
  }

  ////////////////////////////////////////////////////////////////////////////
  // Private Helpers
  ////////////////////////////////////////////////////////////////////////////

  /**
   * Wrap a Dataset action to track all Spark jobs in the body so that we can connect them with
   * an execution.
   */
  private[sql] def withNewExecutionId[U](body: => U): U = {
    SQLExecution.withNewExecutionId(sqlContext, queryExecution)(body)
  }

  /**
   * Wrap a Dataset action to track the QueryExecution and time cost, then report to the
   * user-registered callback functions.
   */
  private def withCallback[U](name: String, df: DataFrame)(action: DataFrame => U) = {
    try {
      df.queryExecution.executedPlan.foreach { plan =>
        plan.resetMetrics()
      }
      val start = System.nanoTime()
      val result = action(df)
      val end = System.nanoTime()
      sqlContext.listenerManager.onSuccess(name, df.queryExecution, end - start)
      result
    } catch {
      case e: Exception =>
        sqlContext.listenerManager.onFailure(name, df.queryExecution, e)
        throw e
    }
  }

  private def withTypedCallback[A, B](name: String, ds: Dataset[A])(action: Dataset[A] => B) = {
    try {
      ds.queryExecution.executedPlan.foreach { plan =>
        plan.resetMetrics()
      }
      val start = System.nanoTime()
      val result = action(ds)
      val end = System.nanoTime()
      sqlContext.listenerManager.onSuccess(name, ds.queryExecution, end - start)
      result
    } catch {
      case e: Exception =>
        sqlContext.listenerManager.onFailure(name, ds.queryExecution, e)
        throw e
    }
  }

  private def sortInternal(global: Boolean, sortExprs: Seq[Column]): Dataset[T] = {
    val sortOrder: Seq[SortOrder] = sortExprs.map { col =>
      col.expr match {
        case expr: SortOrder =>
          expr
        case expr: Expression =>
          SortOrder(expr, Ascending)
      }
    }
    withTypedPlan {
      Sort(sortOrder, global = global, logicalPlan)
    }
  }

  /** A convenient function to wrap a logical plan and produce a DataFrame. */
  @inline private def withPlan(logicalPlan: => LogicalPlan): DataFrame = {
    Dataset.newDataFrame(sqlContext, logicalPlan)
  }

  /** A convenient function to wrap a logical plan and produce a Dataset. */
  @inline private def withTypedPlan(logicalPlan: => LogicalPlan): Dataset[T] = {
    new Dataset[T](sqlContext, logicalPlan, encoder)
  }

  private[sql] def withTypedPlan[R](
      other: Dataset[_], encoder: Encoder[R])(
      f: (LogicalPlan, LogicalPlan) => LogicalPlan): Dataset[R] =
    new Dataset[R](sqlContext, f(logicalPlan, other.logicalPlan), encoder)
}
