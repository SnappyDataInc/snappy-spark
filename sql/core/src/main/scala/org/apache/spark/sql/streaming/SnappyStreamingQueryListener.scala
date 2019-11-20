/*
 * Changes for SnappyData data platform.
 *
 * Portions Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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

package org.apache.spark.sql.streaming

import org.apache.spark.SparkContext

class SnappyStreamingQueryListener(sparkContext: SparkContext) extends StreamingQueryListener {

  val streamingRepo = StreamingRepository.getInstance

  override def onQueryStarted(event: StreamingQueryListener.QueryStartedEvent): Unit = {
    val queryName = {
      if (event.name == null || event.name.isEmpty) {
        event.id.toString
      } else {
        event.name
      }
    }

    streamingRepo.allQueries.put(event.id,
      new StreamingQueryStatistics(
        event.id,
        queryName,
        event.runId,
        System.currentTimeMillis(),
        event.trigger))
  }

  override def onQueryProgress(event: StreamingQueryListener.QueryProgressEvent): Unit = {
    val pr = event.progress
    if (streamingRepo.allQueries.contains(pr.id)) {
      val sqs = streamingRepo.allQueries.get(pr.id).get
      sqs.updateQueryStatistics(event)
    } else {
      val queryName = {
        if (pr.name == null || pr.name.isEmpty) {
          pr.id.toString
        } else {
          pr.name
        }
      }
      val sqs = new StreamingQueryStatistics(
                  pr.id,
                  queryName,
                  pr.runId,
                  System.currentTimeMillis())
      sqs.updateQueryStatistics(event)
      streamingRepo.allQueries.put(pr.id, sqs)
    }
  }

  override def onQueryTerminated(event: StreamingQueryListener.QueryTerminatedEvent): Unit = {
    streamingRepo.allQueries.get(event.id).get.setStatus(false)
  }

}
