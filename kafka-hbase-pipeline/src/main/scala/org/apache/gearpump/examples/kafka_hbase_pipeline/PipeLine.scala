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

import com.typesafe.config.ConfigFactory
import org.apache.gearpump.cluster.UserConfig
import org.apache.gearpump.cluster.client.ClientContext
import org.apache.gearpump.cluster.main.{ArgumentsParser, CLIOption, ParseResult}
import org.apache.gearpump.streaming.kafka.{KafkaSource, KafkaStorageFactory}
import org.apache.gearpump.streaming.source.DataSourceProcessor
import org.apache.gearpump.streaming.{Processor, StreamApplication}
import org.apache.gearpump.util.Graph._
import org.apache.gearpump.util.{Graph, LogUtil}
import org.slf4j.Logger

object PipeLine extends App with ArgumentsParser {
  private val LOG: Logger = LogUtil.getLogger(getClass)
  val PROCESSORS = "pipeline.processors"
  val PERSISTORS = "pipeline.persistors"

  override val options: Array[(String, CLIOption[Any])] = Array(
    "processors"-> CLIOption[Int]("<processor number>", required = false, defaultValue = Some(1)),
    "persistors"-> CLIOption[Int]("<persistor number>", required = false, defaultValue = Some(1)),
    "topic" -> CLIOption[String]("<topic>", required = false, defaultValue = Some("gptest")),
    "brokers" -> CLIOption[String]("<brokers>", required = false, defaultValue = Some("10.10.10.46:9092,10.10.10.164:9092,10.10.10.236:9092")),
    "zookeepers" -> CLIOption[String]("<zookeepers>", required = false, defaultValue = Some("10.10.10.46:2181,10.10.10.236:2181,10.10.10.164:2181/kafka"))
  )

  def application(config: ParseResult): StreamApplication = {
    import Messages._
    val pipelineString =
      """
        |pipeline {
        |  cpu.interval = 20
        |  memory.interval = 20
        |  processors = 1
        |  persistors = 1
        |}
        |hbase {
        |  table {
        |    name = "pipeline"
        |    column {
        |      family = "metrics"
        |      name = "average"
        |    }
        |  }
        |}
      """.stripMargin
    val pipelineConfig = PipeLineConfig(ConfigFactory.parseFile(new java.io.File(pipelineString)))
    val processors = config.getInt("processors")
    val persistors = config.getInt("persistors")
    val topic = config.getString("topic")
    val brokers = config.getString("brokers")
    val zookeepers = config.getString("zookeepers")

    val appConfig = UserConfig.empty.withValue[PipeLineConfig](PIPELINE, pipelineConfig)

    val offsetStorageFactory = new KafkaStorageFactory(zookeepers, brokers)
    val source = new KafkaSource(topic, zookeepers, offsetStorageFactory)
    val kafka = DataSourceProcessor(source, 1)
    val cpuProcessor = Processor[CpuProcessor](processors, "CpuProcessor")
    val memoryProcessor = Processor[MemoryProcessor](processors, "MemoryProcessor")
    val cpuPersistor = Processor[CpuPersistor](persistors, "CpuPersistor")
    val memoryPersistor = Processor[MemoryPersistor](persistors, "MemoryPersistor")
    val app = StreamApplication("KafkaHbasePipeLine", Graph(
      kafka ~> cpuProcessor ~> cpuPersistor,
      kafka ~> memoryProcessor ~> memoryPersistor
    ), appConfig)
    app
  }

  val config = parse(args)
  val context = ClientContext()
  implicit val system = context.system
  val appId = context.submit(application(config))
  context.close()

}
