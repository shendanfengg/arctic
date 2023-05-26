/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.netease.arctic.spark.sql.execution

import scala.collection.JavaConverters._

import org.apache.spark.SparkException
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.expressions.AttributeSet
import org.apache.spark.sql.catalyst.expressions.SortOrder
import org.apache.spark.sql.catalyst.plans.physical
import org.apache.spark.sql.catalyst.util.truncatedString
import org.apache.spark.sql.catalyst.utils.SetAccumulator
import org.apache.spark.sql.connector.iceberg.read.SupportsFileFilter
import org.apache.spark.sql.execution.BinaryExecNode
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.SQLExecution
import org.apache.spark.sql.execution.datasources.v2.DynamicFileFilterExecBase
import org.apache.spark.sql.execution.metric.SQLMetrics
import org.apache.spark.sql.vectorized.ColumnarBatch

case class QueryWithConstraintCheckExec(
    scanExec: SparkPlan,
    fileFilterExec: SparkPlan)
  extends DynamicFileFilterExecBase(scanExec, fileFilterExec) {

  override protected def doPrepare(): Unit = {
    val rows = fileFilterExec.executeCollect()
    if (rows.length > 0) {
      throw new SparkException(
        "There are multiple duplicate primary key data in the inserted data, " +
          "which cannot guarantee the uniqueness of the primary key. ")
    }
  }
}
