/*
 * Copyright 2015 Databricks Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.databricks.spark.sql.perf

import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.analysis.UnresolvedRelation

/**
 * The execution mode of a benchmark:
 * - CollectResults: Benchmark run by collecting queries results
 *   (e.g. rdd.collect())
 * - ForeachResults: Benchmark run by iterating through the queries results rows
 *   (e.g. rdd.foreach(row => Unit))
 * - WriteParquet(location): Benchmark run by saving the output of each query as a
 *   parquet file at the specified location
 */
abstract class ExecutionMode
case object CollectResults extends ExecutionMode
case object ForeachResults extends ExecutionMode
case class WriteParquet(location: String) extends ExecutionMode

case class Query(name: String, sqlText: String, description: String, executionMode: ExecutionMode)

case class QueryForTest(
    query: Query,
    includeBreakdown: Boolean,
    @transient sqlContext: SQLContext) {
  @transient val sparkContext = sqlContext.sparkContext

  val name = query.name

  def benchmarkMs[A](f: => A): Double = {
    val startTime = System.nanoTime()
    val ret = f
    val endTime = System.nanoTime()
    (endTime - startTime).toDouble / 1000000
  }

  def benchmark(description: String = "", queryOutputLocation: Option[String]) = {
    try {
      sparkContext.setJobDescription(s"Query: ${query.name}, $description")
      val dataFrame = sqlContext.sql(query.sqlText)
      val queryExecution = dataFrame.queryExecution
      // We are not counting the time of ScalaReflection.convertRowToScala.
      val parsingTime = benchmarkMs { queryExecution.logical }
      val analysisTime = benchmarkMs { queryExecution.analyzed }
      val optimizationTime = benchmarkMs { queryExecution.optimizedPlan }
      val planningTime = benchmarkMs { queryExecution.executedPlan }

      val breakdownResults = if (includeBreakdown) {
        val depth = queryExecution.executedPlan.treeString.split("\n").size
        val physicalOperators = (0 until depth).map(i => (i, queryExecution.executedPlan(i)))
        physicalOperators.map {
          case (index, node) =>
            val executionTime = benchmarkMs { node.execute().map(_.copy()).foreach(row => Unit) }
            BreakdownResult(node.nodeName, node.simpleString, index, executionTime)
        }
      } else {
        Seq.empty[BreakdownResult]
      }

      // The executionTime for the entire query includes the time of type conversion from catalyst to scala.
      val executionTime = query.executionMode match {
        case CollectResults => benchmarkMs { dataFrame.rdd.collect() }
        case ForeachResults => benchmarkMs { dataFrame.rdd.foreach { row => Unit } }
        case WriteParquet(location) => benchmarkMs { dataFrame.saveAsParquetFile(s"$location/${query.name}.parquet") }
      }

      val joinTypes = dataFrame.queryExecution.executedPlan.collect {
        case k if k.nodeName contains "Join" => k.nodeName
      }

      val tablesInvolved = dataFrame.queryExecution.logical collect {
        case UnresolvedRelation(tableIdentifier, _) => {
          // We are ignoring the database name.
          tableIdentifier.last
        }
      }

      queryOutputLocation.foreach(dir => dataFrame.saveAsParquetFile(s"$dir/$name.parquet"))

      BenchmarkResult(
        name = query.name,
        joinTypes = joinTypes,
        tables = tablesInvolved,
        parsingTime = parsingTime,
        analysisTime = analysisTime,
        optimizationTime = optimizationTime,
        planningTime = planningTime,
        executionTime = executionTime,
        breakdownResults)
    } catch {
      case e: Exception =>
        throw new RuntimeException(
          s"Failed to benchmark query ${query.name}", e)
    }
  }
}
