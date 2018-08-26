package com.evolutiongaming.skafka.producer

import com.evolutiongaming.concurrent.CurrentThreadExecutionContext
import com.evolutiongaming.skafka.ToBytes
import io.prometheus.client.{CollectorRegistry, Counter, Summary}

import scala.compat.Platform
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

object PrometheusProducer {

  def apply(
    producer: Producer,
    registry: CollectorRegistry,
    prefix: String = "skafka_producer",
    label: String = "skafka"): Producer = {

    implicit val ec = CurrentThreadExecutionContext

    val latencySummary = Summary.build()
      .name(s"${ prefix }_latency")
      .help("Latency in seconds")
      .labelNames("producer", "topic")
      .quantile(0.9, 0.01)
      .quantile(0.99, 0.001)
      .register(registry)

    val bytesSummary = Summary.build()
      .name(s"${ prefix }_bytes")
      .help("Message size in bytes")
      .labelNames("producer", "topic")
      .register(registry)

    val counter = Counter.build()
      .name(s"${ prefix }_result")
      .help("Result: success or failure")
      .labelNames("producer", "topic", "result")
      .register(registry)

    new Producer {

      def send[K, V](record: ProducerRecord[K, V])
        (implicit valueToBytes: ToBytes[V], keyToBytes: ToBytes[K]) = {

        val start = Platform.currentTime
        val result = producer.send(record)(valueToBytes, keyToBytes)
        result.onComplete { result =>
          val topicLabel = record.topic.replace(".", "_")
          val duration = (Platform.currentTime - start).toDouble / 1000
          latencySummary
            .labels(label, topicLabel)
            .observe(duration)

          val resultLabel = result match {
            case Success(metadata) =>
              bytesSummary
                .labels(label, topicLabel)
                .observe(metadata.serializedValueSize.toDouble)
              "success"
            case Failure(_)        =>
              "failure"
          }
          counter
            .labels(label, topicLabel, resultLabel)
            .inc()
        }
        result
      }

      def flush() = {
        producer.flush()
      }

      def close(timeout: FiniteDuration) = {
        producer.close(timeout)
      }

      def close() = {
        producer.close()
      }
    }
  }
}
