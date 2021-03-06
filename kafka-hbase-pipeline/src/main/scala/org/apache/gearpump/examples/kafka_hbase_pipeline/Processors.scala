/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gearpump.examples.kafka_hbase_pipeline

import com.typesafe.config.Config
import org.apache.gearpump._
import org.apache.gearpump.cluster.UserConfig
import org.apache.gearpump.examples.kafka_hbase_pipeline.Messages._
import org.apache.gearpump.external.hbase.HBaseSink
import org.apache.gearpump.external.hbase.HBaseSink._
import org.apache.gearpump.streaming.task.{StartTime, Task, TaskContext}
import org.apache.gearpump.util.LogUtil
import org.slf4j.Logger
import upickle._

import scala.language.implicitConversions
import scala.util.Try

// NOTE: Do not split up into separate files
// See http://stackoverflow.com/questions/28630780/upickle-and-scalajs-sealed-trait-serialisation
object Messages {
  val PIPELINE = "PipeLine"
  val DEFAULT_INTERVAL = 2
  val CPU = "CPU"
  val CPU_INTERVAL = "pipeline.cpu.interval"
  val MEM = "MEM"
  val MEM_INTERVAL = "pipeline.memory.interval"

  case class Datum(dimension: String, metric: String, value: Double) extends java.io.Serializable

  case class Body(sample_id: String, source_id: String, event_ts: String, metrics: Array[Datum])

  case class Envelope(id: String, on: String, body: String)
}

case class PipeLineConfig(config: Config) extends java.io.Serializable

class TAverage(interval: Int) extends java.io.Serializable {
  val LOG: Logger = LogUtil.getLogger(getClass)
  var averageValue: Double = 0
  var totalCount: Long = 0
  var totalSum: Double = 0
  var timeStamp: TimeStamp = 0
  def timeInterval: Int = interval
  def average(datum: Datum, ts: TimeStamp): Option[Datum] = {
    totalCount += 1
    totalSum += datum.value
    averageValue = totalSum/totalCount
    interval(datum, ts)
  }
  def elapsedInSec(ts: Long): Long = ts - timeStamp
  def interval(datum: Datum, ts: TimeStamp): Option[Datum] = {
    timeStamp match {
      case 0 =>
        timeStamp = ts
        None
      case _ =>
        val elapsed = elapsedInSec(ts)
        elapsed match {
          case delta if delta > timeInterval =>
            timeStamp = ts
            //LOG.info(s"elapsed=$elapsed")
            Some(Datum(datum.dimension,datum.metric,averageValue))
          case _ =>
            None
        }
    }
  }
}

object TAverage {
  def apply(interval: Int): TAverage = new TAverage(interval)
}

abstract class MetricProcessor(taskContext: TaskContext, conf: UserConfig)
  extends Task(taskContext, conf) {

  val pipelineConfig = conf.getValue[PipeLineConfig](PIPELINE)
  val timeInterval: Int
  val average: TAverage = TAverage(timeInterval)

  def average(datum: Datum, timeStamp: TimeStamp): Option[Datum] = {
    average.average(datum, timeStamp: TimeStamp)
  }

  override def onStart(newStartTime: StartTime): Unit = {
    LOG.info(s"starting timeInterval=$timeInterval")
  }

}

class CpuProcessor(taskContext: TaskContext, conf: UserConfig)
  extends MetricProcessor(taskContext, conf) {
  import taskContext.output

  override val timeInterval = pipelineConfig.map(config => {
    config.config.getInt(CPU_INTERVAL)
  }).getOrElse(DEFAULT_INTERVAL)

  override def onNext(msg: Message): Unit = {
    Try({
      val jsonData = msg.msg.asInstanceOf[String]
      val metrics = read[Array[Datum]](jsonData)
      val data = metrics.flatMap(datum => {
        datum.dimension match {
          case CPU =>
            val results = average(datum, msg.timestamp)
            LOG.info(s"returning $results")
            results
          case _ =>
            None
        }
      })
      output(Message(write[Array[Datum]](data),msg.timestamp))
    }).failed.foreach(LOG.error("bad message", _))
  }
}

class MemoryProcessor(taskContext: TaskContext, conf: UserConfig)
  extends MetricProcessor(taskContext, conf) {

  import taskContext.output

  override val timeInterval = pipelineConfig.map(config => {
    config.config.getInt(MEM_INTERVAL)
  }).getOrElse(DEFAULT_INTERVAL)

  override def onNext(msg: Message): Unit = {
    Try({
      val jsonData = msg.msg.asInstanceOf[String]
      val metrics = read[Array[Datum]](jsonData)
      val data = metrics.flatMap(datum => {
        datum.dimension match {
          case MEM =>
            average(datum, msg.timestamp)
          case _ =>
            None
        }
      }).toSeq.toArray
      output(Message(write[Array[Datum]](data),msg.timestamp))
    }).failed.foreach(LOG.error("bad message", _))
  }
}

class CpuPersistor(taskContext : TaskContext, conf: UserConfig)
  extends Task(taskContext, conf) {

  def userConf = conf
  val pipeLineConfig: PipeLineConfig = userConf.getValue[PipeLineConfig](PIPELINE).get
  val hbaseTable = pipeLineConfig.config.getString("hbase.table.name")
  val hbaseFamily = pipeLineConfig.config.getString("hbase.table.column.family")
  val hbaseColumn = pipeLineConfig.config.getString("hbase.table.column.name")
  lazy val hbase = userConf.getValue[HBaseSink](HBASESINK) match {
    case Some(hbaseSink) =>
      hbaseSink
    case None =>
      HBaseSink(hbaseTable)
  }

  override def onStart(newStartTime: StartTime): Unit = {
    LOG.info("starting")
  }

  override def onNext(msg: Message): Unit = {
    Try({
      val cpus = read[Array[Datum]](msg.msg.asInstanceOf[String])
      cpus.foreach(cpu => {
        hbase.insert(msg.timestamp.toString, hbaseFamily, hbaseColumn, write[Datum](cpu))
      })
    }).failed.foreach(LOG.error("bad message", _))
  }

}

class MemoryPersistor(taskContext : TaskContext, conf: UserConfig)
  extends Task(taskContext, conf) {

  def userConf = conf
  val pipeLineConfig: PipeLineConfig = userConf.getValue[PipeLineConfig](PIPELINE).get
  val hbaseTable = pipeLineConfig.config.getString("hbase.table.name")
  val hbaseFamily = pipeLineConfig.config.getString("hbase.table.column.family")
  val hbaseColumn = pipeLineConfig.config.getString("hbase.table.column.name")
  lazy val hbase = userConf.getValue[HBaseSink](HBASESINK) match {
    case Some(hbaseSink) =>
      hbaseSink
    case None =>
      HBaseSink(hbaseTable)
  }
  override def onStart(newStartTime: StartTime): Unit = {
    LOG.info("starting")
  }

  override def onNext(msg: Message): Unit = {
    Try({
      val memories = read[Array[Datum]](msg.msg.asInstanceOf[String])
      memories.foreach(memory => {
        hbase.insert(msg.timestamp.toString, hbaseFamily, hbaseColumn, write[Datum](memory))
      })
    }).failed.foreach(LOG.error("bad message", _))
  }

}







