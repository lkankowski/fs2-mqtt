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

package net.sigusr.mqtt.impl.protocol

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.event.LoggingReceive
import akka.util.ByteString
import net.sigusr.mqtt.api.MQTTAPIMessage
import net.sigusr.mqtt.impl.frames.Frame
import net.sigusr.mqtt.impl.protocol.Transport.PingRespTimeout
import scodec.Codec
import scodec.bits.BitVector

object Transport {
  private[protocol] sealed trait InternalAPIMessage
  private[protocol] case object SendKeepAlive extends InternalAPIMessage
  private[protocol] case object PingRespTimeout extends InternalAPIMessage
}

trait Transport

abstract class TCPTransport(client: ActorRef, mqttBrokerAddress: InetSocketAddress) extends Actor with Transport { this: Client with Protocol =>

  import akka.io.Tcp._
  import context.dispatcher
  import net.sigusr.mqtt.impl.protocol.Transport.{InternalAPIMessage, SendKeepAlive}

  import scala.concurrent.duration.FiniteDuration

  var keepAliveValue : Option[FiniteDuration] = None
  var keepAliveTask: Option[Cancellable] = None
  var pingResponseTask: Option[Cancellable] = None

  tcpManagerActor ! Connect(mqttBrokerAddress)

  def receive = LoggingReceive {
    case CommandFailed(_ : Connect) =>
      processAction(transportNotReady())
      context stop self
    case Connected(_, _) =>
      tcpManagerActor ! Register(self)
      processAction(transportReady())
      context become connected()
  }

  def connected(): Receive = LoggingReceive {
    case message : MQTTAPIMessage =>
      handleApiMessages(message).foreach((action: Action) => processAction(action))
    case internalMessage: InternalAPIMessage =>
      handleInternalApiMessages(internalMessage).foreach((action: Action) => processAction(action))
    case Received(encodedResponse) ⇒
      val frame: Frame = Codec[Frame].decodeValidValue(BitVector.view(encodedResponse.toArray))
      handleNetworkFrames(frame).foreach((action: Action) => processAction(action))
    case _: ConnectionClosed ⇒
      processAction(connectionClosed())
      context stop self
    case CommandFailed(w: Write) ⇒ // O/S buffer was full
  }

  def processAction(action: Action) = {
    action match {
      case SetKeepAliveValue(duration) =>
        keepAliveValue = Some(duration)
      case StartKeepAliveTimer =>
        keepAliveValue foreach { k =>
          keepAliveTask = Some(context.system.scheduler.schedule(k, k, self, SendKeepAlive))
        }
      case StartPingResponseTimer =>
        keepAliveValue foreach { k =>
          pingResponseTask = Some(context.system.scheduler.scheduleOnce(k, self, PingRespTimeout))
        }
      case CancelPingResponseTimer =>
        pingResponseTask foreach { _.cancel() }
      case SendToClient(message) =>
        client ! message
      case SendToNetwork(frame) =>
        val encodedFrame = Codec[Frame].encodeValid(frame)
        tcpManagerActor ! Write(ByteString(encodedFrame.toByteArray))
      case CloseTransport =>
        tcpManagerActor ! Close
    }
  }

  override def postStop(): Unit = {
    keepAliveTask foreach { _.cancel() }
    pingResponseTask foreach { _.cancel() }
  }
}




