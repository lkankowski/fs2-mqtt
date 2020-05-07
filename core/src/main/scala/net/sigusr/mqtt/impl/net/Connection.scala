/*
 * Copyright 2020 Frédéric Cabestre
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.sigusr.mqtt.impl.net

import cats.effect.concurrent.Deferred
import cats.effect.{ Concurrent, ContextShift, Resource, Timer }
import cats.implicits._
import fs2.Stream
import net.sigusr.mqtt.api.Errors.ProtocolError
import net.sigusr.mqtt.api.QualityOfService
import net.sigusr.mqtt.api.QualityOfService.AtMostOnce
import net.sigusr.mqtt.impl.frames._
import net.sigusr.mqtt.impl.net.Builders._
import net.sigusr.mqtt.impl.net.Result.QoS

sealed case class Will(retain: Boolean, qos: QualityOfService, topic: String, message: String)
sealed case class Message(topic: String, payload: Vector[Byte])

sealed case class Config(
  clientId: String,
  keepAlive: Int = DEFAULT_KEEP_ALIVE,
  cleanSession: Boolean = true,
  will: Option[Will] = None,
  user: Option[String] = None,
  password: Option[String] = None)

trait Connection[F[_]] {

  def disconnect: F[Unit]

  def messages(): Stream[F, Message]

  def subscribe(topics: Vector[(String, QualityOfService)]): F[Vector[(String, QualityOfService)]]

  def unsubscribe(topics: Vector[String]): F[Unit]

  def publish(topic: String, payload: Vector[Byte], qos: QualityOfService = AtMostOnce, retain: Boolean = false): F[Unit]

}

object Connection {

  def apply[F[_]: Concurrent: Timer: ContextShift](
    brockerConnector: BrokerConnector[F],
    config: Config): Resource[F, Connection[F]] =
    Resource.make(fromBrokerConnector(brockerConnector, config))(_.disconnect)

  private def fromBrokerConnector[F[_]: Concurrent: Timer: ContextShift](
    brockerConnector: BrokerConnector[F],
    config: Config): F[Connection[F]] = for {
    ids <- IdGenerator[F]
    engine <- Engine(brockerConnector, config.keepAlive.toLong)
    _ <- engine.connect(config)
  } yield new Connection[F] {

    override val disconnect: F[Unit] = {
      val disconnectMessage = DisconnectFrame(Header())
      ids.cancel *> engine.send(disconnectMessage)
    }

    override val messages: Stream[F, Message] = engine.messages

    override def subscribe(topics: Vector[(String, QualityOfService)]): F[Vector[(String, QualityOfService)]] = {
      for {
        messageId <- ids.next
        v <- engine.sendReceive(subscribeFrame(messageId, topics), messageId)
      } yield v match {
        case QoS(t) => topics.zip(t).map(p => (p._1._1, QualityOfService.withValue(p._2)))
        case _ => throw ProtocolError
      }
    }

    override def unsubscribe(topics: Vector[String]): F[Unit] = {
      for {
        messageId <- ids.next
        _ <- engine.sendReceive(unsubscribeFrame(messageId, topics), messageId)
      } yield ()
    }

    override def publish(topic: String, payload: Vector[Byte], qos: QualityOfService, retain: Boolean): F[Unit] = {
      qos match {
        case QualityOfService.AtMostOnce =>
          engine.send(publishFrame(topic, None, payload, qos, retain))
        case QualityOfService.AtLeastOnce | QualityOfService.ExactlyOnce => for {
          messageId <- ids.next
          _ <- engine.sendReceive(publishFrame(topic, Some(messageId), payload, qos, retain), messageId)
        } yield ()
      }
    }
  }
}