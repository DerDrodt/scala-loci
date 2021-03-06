package loci
package runtime

import communicator.{Connection, Connector, Listener}
import messaging.{ConnectionsBase, Message}
import transmitter.RemoteAccessException

import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

class RemoteConnections(peer: Peer.Signature, ties: Map[Peer.Signature, Peer.Tie], uuid: Option[String] = None)
  extends ConnectionsBase[Remote.Reference, Message[Method]] {

  protected def deserializeMessage(message: MessageBuffer) = {
    val result = Message.deserialize[Method](message)
    result.failed foreach {
      logging.warn("could not parse message", _)
    }
    result
  }

  protected def serializeMessage(message: Message[Method]) =
    Message.serialize(message)

  private def violatedException =
    new RemoteAccessException("tie constraints violated")

  private def messageException(message: Message[Method]) =
    new Message.Exception(s"unexpected connect message: $message")

  private val multiplicities =
    (ties.keys flatMap {
      _.bases
    } map {
      _ -> Peer.Tie.Multiple
    }).toMap ++ ties

  protected class State(uuid: Option[String] = None) extends BaseState {
    private val counter = new AtomicLong(1)

    def createId() = counter.getAndIncrement()

    val potentials = mutable.ListBuffer.empty[Peer.Signature]
    val hereUUID: String = uuid match {
      case Some(u) => u
      case None => java.util.UUID.randomUUID().toString
    }
    val remoteToUUID = mutable.Map.empty[Remote.Reference, String]
    val remoteToSig = mutable.Map.empty[Remote.Reference, Peer.Signature]
  }

  val state = new State(uuid)

  private val doConstraintsSatisfied = Notice.Stream[Unit]

  private val doConstraintsViolated = Notice.Stream[Unit]

  def constraintsSatisfied: Notice.Stream[Unit] = doConstraintsSatisfied.notice

  def constraintsViolated: Notice.Stream[Unit] = doConstraintsViolated.notice

  def connect(
               connector: Connector[ConnectionsBase.Protocol],
               remotePeer: Peer.Signature): Notice.Steady[Try[Remote.Reference]] = {
    val connected = Notice.Steady[Try[Remote.Reference]]
    connectWithCallback(connector, remotePeer)(connected.set)
    connected.notice
  }

  def connectWithCallback(
                           connector: Connector[ConnectionsBase.Protocol],
                           remotePeer: Peer.Signature)(
                           handler: Try[Remote.Reference] => Unit): Unit = sync {
    if (!isTerminated) {
      if (constraintViolationsConnecting(remotePeer).isEmpty) {
        state.potentials += remotePeer

        connector.connect() {
          case Success(connection) =>
            val remote = Remote.Reference(
              state.createId(), remotePeer)(
              connection.protocol, this)

            var closedHandler: Notice[_] = null
            var receiveHandler: Notice[_] = null

            closedHandler = connection.closed foreach { _ =>
              handler(Failure(terminatedException))
            }

            receiveHandler = connection.receive foreach { data =>
              sync {
                state.potentials -= remotePeer

                if (receiveHandler != null)
                  receiveHandler.remove()
                if (closedHandler != null)
                  closedHandler.remove()

                val handleAccept =
                  handleAcceptMessage(connection, remote, remotePeer)
                val handleRequest =
                  handleRequestMessage(connection, remotePeer) andThen {
                    _ map { case (remote, _) => remote }
                  }

                val result = deserializeMessage(data) flatMap {
                  handleAccept orElse handleRequest orElse
                    handleUnknownMessage
                }

                if (result.isFailure)
                  connection.close()

                afterSync {
                  handler(result)
                }
              }
            }

            logging.trace(s"connecting to remote $remotePeer")

            connection.send(serializeMessage(
              RequestMessage(
                Peer.Signature.serialize(remotePeer),
                Peer.Signature.serialize(peer),
                state.hereUUID)))

          case Failure(exception) =>
            logging.trace(s"connecting to remote failed: $remotePeer", exception)
            handler(Failure(exception))
        }
      }
      else {
        logging.trace(s"connection refused due to tie constraints: $remotePeer")
        handler(Failure(violatedException))
      }
    }
    else {
      logging.trace(s"connection refused after connection system shutdown: $remotePeer")
      handler(Failure(terminatedException))
    }
  }

  def listen(
              listener: Listener[ConnectionsBase.Protocol],
              remotePeer: Peer.Signature,
              createDesignatedInstance: Boolean = false): Try[Unit] =
    listenWithCallback(listener, remotePeer, createDesignatedInstance) { _ => }

  def listenWithCallback(
                          listener: Listener[ConnectionsBase.Protocol],
                          remotePeer: Peer.Signature,
                          createDesignatedInstance: Boolean = false)(
                          handler: Try[(Remote.Reference, RemoteConnections)] => Unit): Try[Unit] =
    sync {
      if (!isTerminated) {
        val listening = listener.startListening() {
          case Success(connection) =>
            var receiveHandler: Notice[_] = null

            receiveHandler = connection.receive foreach { data =>
              if (receiveHandler != null)
                receiveHandler.remove()

              val handleRequest = handleRequestMessage(
                connection, remotePeer, createDesignatedInstance, listener)

              val result = deserializeMessage(data) flatMap {
                handleRequest orElse handleUnknownMessage
              }

              if (result.isFailure)
                connection.close()

              handler(result)
            }

          case Failure(exception) =>
            handler(Failure(exception))
        }

        listening foreach addListening

        listening map { _ => () }
      }
      else
        Failure(terminatedException)
    }

  protected def handleRequestMessage(
      connection: Connection[ConnectionsBase.Protocol],
      remotePeer: Peer.Signature,
      createDesignatedInstance: Boolean = false,
      listener: Listener[ConnectionsBase.Protocol] = null,
      replaceUUID: Option[String] = None,
      remoteRef: Option[Remote.Reference] = None)
  : PartialFunction[Message[Method], Try[(Remote.Reference, RemoteConnections)]] = {
    case RequestMessage(requested, requesting, requestingUUID) =>
      sync {
        if (!isTerminated)
          Peer.Signature.deserialize(requested) flatMap { requestedPeer =>
            Peer.Signature.deserialize(requesting) flatMap { requestingPeer =>
              if (peer <= requestedPeer &&
                requestingPeer <= remotePeer &&
                constraintViolationsConnecting(remotePeer).isEmpty) {
                val instance =
                  if (!createDesignatedInstance) this
                  else new RemoteConnections(peer, ties)

                val remote = replaceUUID match {
                  case Some(requestingUUID) => remoteRef.get
                  case _ => Remote.Reference(
                    instance.state.createId(), remotePeer)(
                    connection.protocol, this)
                }

                state.remoteToUUID.addOne((remote, requestingUUID))
                state.remoteToSig.addOne((remote, remotePeer))

                connection.send(serializeMessage(AcceptMessage(state.hereUUID)))

                val result = instance.addConnection(remote, connection)

                result map { _ => (remote, instance) }
              }
              else
                Failure(violatedException)
            }
          }
        else
          Failure(terminatedException)
      }
  }

  protected def handleAcceptMessage(
                                   connection: Connection[ConnectionsBase.Protocol],
                                   remote: Remote.Reference,
                                   remotePeer: Peer.Signature,
                                   replaceUUID: Option[String] = None,
                                   replaceRef: Option[Remote.Reference] = None)
  : PartialFunction[Message[Method], Try[Remote.Reference]] = {
    case AcceptMessage(uuid) =>
      sync {
        if (!isTerminated) {
          val remoteRef = replaceUUID match {
            case Some(uuid) => replaceRef.get
            case _ => remote
          }
          state.remoteToUUID.addOne((remoteRef, uuid))
          state.remoteToSig.addOne((remoteRef, remotePeer))
          addConnection(remoteRef, connection) map { _ => remoteRef }
        }
        else
          Failure(terminatedException)
      }
  }

  private val handleUnknownMessage
  : PartialFunction[Message[Method], Try[Nothing]] = {
    case message => Failure(messageException(message))
  }

  override protected def addConnection(
                                        remote: Remote.Reference,
                                        connection: Connection[ConnectionsBase.Protocol]): Try[Unit] =
    sync {
      handleConstraintChanges {
        super.addConnection(remote, connection)
      }
    }

  override def removeConnection(remote: Remote.Reference): Unit =
    sync {
      handleConstraintChanges {
        super.removeConnection(remote)
      }
    }

  def bufferMessages(remote: Remote.Reference): Unit = sync {
    if (!state.isTerminated) {
      if (state.connections containsKey remote) {
        logging.info(s"buffering messages to $remote")

        state.bufferedRemotes.add(remote)
      }
    } else
      Failure(terminatedException)
  }

  def sendBufferedMessages(remote: Remote.Reference): Unit = sync {
    if (!state.isTerminated) {
      if (state.bufferedRemotes contains remote) {
        logging.info(s"sending buffered messages to $remote")
        state.bufferedRemotes.remove(remote)
        state.outgoingMessages.foreach(t => {
          val (ref, msg) = t
          if (ref == remote) {
            send(remote, msg)
          }
        })
      }
    } else
      Failure(terminatedException)
  }

  private def handleConstraintChanges[T](changeConnection: => T): T =
    if (!state.isTerminated) {
      val constraintsSatisfiedBefore = constraintViolations.isEmpty
      val result = changeConnection
      val constraintsSatisfiedAfter = constraintViolations.isEmpty

      if (!constraintsSatisfiedBefore && constraintsSatisfiedAfter)
        afterSync {
          doConstraintsSatisfied.fire()
        }
      if (constraintsSatisfiedBefore && !constraintsSatisfiedAfter)
        afterSync {
          doConstraintsViolated.fire()
        }

      result
    }
    else
      changeConnection

  def constraintViolationsConnecting(peer: Peer.Signature): Option[Peer.Signature] = {
    val peerCounts = connections(includePotentials = true) count {
      _ == peer
    }

    if (!checkConstraints(peer, 1 + peerCounts)) {
      logging.trace(s"Constraints violated for single checked peer $peer")
      Some(peer)
    }
    else
      None
  }

  def constraintViolations: Set[Peer.Signature] = {
    val peerCounts =
      (multiplicities map { case (peer, _) => (peer, 0) }) ++
        (connections(includePotentials = false) groupBy identity map {
          case (peer, list) => (peer, list.size)
        })

    val violations =
      (peerCounts collect { case (peer, count)
        if !checkConstraints(peer, count) => peer
      }).toSet

    if (violations.nonEmpty)
      logging.trace(s"Constraints violated for [${violations mkString ", "}]")

    violations
  }

  private def connections(includePotentials: Boolean): Seq[Peer.Signature] = {
    val remotePeers = remotes map {
      _.signature
    }
    val potentialPeers =
      if (includePotentials) synchronized {
        state.potentials
      }
      else Seq.empty

    logging.trace({
      val connectedPeers =
        s"checking constraints for connected remote peer instances with types [${remotePeers mkString ", "}]"
      if (includePotentials)
        s"$connectedPeers and connecting remote peer instances with types [${potentialPeers mkString ", "}]"
      else
        connectedPeers
    })

    (remotePeers ++ potentialPeers) flatMap {
      _.bases
    }
  }

  protected def checkConstraints(peer: Peer.Signature, count: Int): Boolean =
    peer.bases collect (Function unlift { peer =>
      multiplicities get peer map {
        case Peer.Tie.Multiple => true
        case Peer.Tie.Optional => count <= 1
        case Peer.Tie.Single => count == 1
      }
    }) reduceOption {
      _ && _
    } getOrElse false
}

