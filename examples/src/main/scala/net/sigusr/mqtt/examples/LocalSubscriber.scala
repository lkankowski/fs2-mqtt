/*
 * Copyright 2014 Frédéric Cabestre
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

package net.sigusr.mqtt.examples

import cats.effect.std.Console
import cats.implicits._
import com.comcast.ip4s.{Host, Port}
import fs2.Stream
import fs2.concurrent.SignallingRef
import net.sigusr.mqtt.api.QualityOfService.{AtLeastOnce, AtMostOnce, ExactlyOnce}
import net.sigusr.mqtt.api.RetryConfig.Custom
import net.sigusr.mqtt.api._
import retry.RetryPolicies
import zio._
import zio.duration.Duration
import zio.interop.catz._
import zio.interop.catz.implicits._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

object LocalSubscriber extends App {

  private val stopTopic: String = s"$localSubscriber/stop"
  private val subscribedTopics: Vector[(String, QualityOfService)] = Vector(
    (stopTopic, ExactlyOnce),
    ("AtMostOnce", AtMostOnce),
    ("AtLeastOnce", AtLeastOnce),
    ("ExactlyOnce", ExactlyOnce)
  )

  private val unsubscribedTopics: Vector[String] = Vector("AtMostOnce", "AtLeastOnce", "ExactlyOnce")

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val retryConfig: Custom[Task] = Custom[Task](
      RetryPolicies
        .limitRetries[Task](5)
        .join(RetryPolicies.fullJitter[Task](2.seconds))
    )
    val transportConfig =
      TransportConfig[Task](
        Host.fromString("127.0.0.1").get,
        Port.fromString("1883").get,
        // TLS support looks like
        // 8883,
        // tlsConfig = Some(TLSConfig(TLSContextKind.System)),
        retryConfig = retryConfig,
        traceMessages = true
      )
    val sessionConfig =
      SessionConfig(
        s"$localSubscriber",
        cleanSession = false,
        user = Some("username"),
        password = Some("password"),
        keepAlive = 5
      )
    implicit val console: Console[Task] = Console.make[Task]
    Session[Task](transportConfig, sessionConfig).use { session =>
      SignallingRef[Task, Boolean](false)
        .flatMap { stopSignal =>
          val sessionStatus = session.state.discrete
            .evalMap(logSessionStatus[Task])
            .evalMap(onSessionError[Task])
            .interruptWhen(stopSignal)
            .compile
            .drain
          val subscriber = for {
            s <- session.subscribe(subscribedTopics)
            _ <- s.traverse { p =>
              putStrLn[Task](
                s"Topic ${scala.Console.CYAN}${p._1}${scala.Console.RESET} subscribed with QoS " +
                  s"${scala.Console.CYAN}${p._2.show}${scala.Console.RESET}"
              )
            }
            _ <- ZIO.sleep(Duration(23, TimeUnit.SECONDS))
            _ <- session.unsubscribe(unsubscribedTopics)
            _ <- putStrLn[Task](s"Topic ${scala.Console.CYAN}${unsubscribedTopics.mkString(", ")}${scala.Console.RESET} unsubscribed")
            _ <- stopSignal.discrete.compile.drain
          } yield ()
          val reader = session.messages.flatMap(processMessages(stopSignal)).interruptWhen(stopSignal).compile.drain
          for {
            _ <- sessionStatus <&> subscriber.race(reader)
          } yield ()
        }
        .asInstanceOf[Task[Boolean]]
    }
  }.fold(_ => ExitCode.failure, _ => ExitCode.success)

  private def processMessages(stopSignal: SignallingRef[Task, Boolean]): Message => Stream[Task, Unit] = {
    case Message(LocalSubscriber.stopTopic, _) => Stream.exec(stopSignal.set(true))
    case Message(topic, payload) =>
      Stream.eval(Task {
        println(
          s"Topic ${scala.Console.CYAN}$topic${scala.Console.RESET}: " +
            s"${scala.Console.BOLD}${new String(payload.toArray, "UTF-8")}${scala.Console.RESET}"
        )
      })
  }
}
