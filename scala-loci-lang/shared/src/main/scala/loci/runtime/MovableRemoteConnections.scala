package loci.runtime

import loci.{MessageBuffer, logging}
import loci.messaging.{ConnectionsBase, Message}
import loci.communicator.{Connection, Connector, Listener, ProtocolCommon}

import scala.util.{Failure, Success, Try}

class MovableRemoteConnections(peer: Peer.Signature, ties: Map[Peer.Signature, Peer.Tie], uuid: Option[String]) extends RemoteConnections(peer, ties, uuid) {
  private var expectMoveRemote: Option[(Remote.Reference, Boolean, String)] = None
  private var ignoreViolationsFor: Option[Peer.Signature] = None

  override protected def deserializeMessage(message: MessageBuffer) = {
    val result = Message.deserialize[Method](message)
    result.failed foreach {
      logging.warn("could not parse message", _)
    }
    result
  }

  def expectMove(ref: Remote.Reference, uuid: String): Try[Unit] = {
    if (!state.bufferedRemotes.contains(ref)) {
      return Failure(new Exception(s"Cannot move remote $ref because it is not buffered"))
    }
    if (expectMoveRemote.isDefined && expectMoveRemote.get._1 != ref) {
      return Failure(new Exception(s"Already moving another remote"))
    }
    val setup = state.connections.get(ref).protocol.setup
    val listen = setup match {
      case _: Connector[ProtocolCommon] => false
      case _ => true
    }
    val sig = state.remoteToSig(ref)
    ignoreViolationsFor = Some(sig)
    println("Ignoring constraint violations for signature " + sig.toString)
    expectMoveRemote = Some(ref, listen, uuid)
    Success()
  }

  // As I see it, there are two cases:
  // 1. Listen:
  // This is the "easy" one. The connection we want to replace is a listen connection.
  // Idea: just wait for next connection to this listener (ideally only of same peer signature, but we'd have to store that first)
  // 2. Connect:
  // The hard one. We have to build a new connection, which means we need to know connection type, host and port
  // Maybe we just let the dev handle this? The question then becomes what API we expose.

  // Case 1
  override protected def handleRequestMessage(
                                               connection: Connection[ConnectionsBase.Protocol],
                                               remotePeer: Peer.Signature,
                                               createDesignatedInstance: Boolean = false,
                                               listener: Listener[ConnectionsBase.Protocol] = null,
                                               remoteRef: Option[Remote.Reference] = None)
  : PartialFunction[Message[Method], Try[(Remote.Reference, RemoteConnections)]] = {
    if (expectMoveRemote.isDefined) {
      val (remote, listen, uuid) = expectMoveRemote.get
      if (listen) {
        println("Got request message while awaiting move")
        expectMoveRemote = None
        return super.handleRequestMessage(connection, remotePeer, createDesignatedInstance, listener, Some(remote))
      }
    }
    super.handleRequestMessage(connection, remotePeer, createDesignatedInstance, listener, remoteRef)
  }

  // Case 2
  override protected def handleAcceptMessage(
                                              connection: Connection[ConnectionsBase.Protocol],
                                              remote: Remote.Reference,
                                              remotePeer: Peer.Signature)
  : PartialFunction[Message[Method], Try[Remote.Reference]] = {
    expectMoveRemote match {
      case Some((ref, false, uuid)) => {
        // The only issue here as far as I can see would be that there is a new Remote.Reference that was created,
        // but is unused. Maybe that breaks some assumptions?
        println("Got accept message while awaiting move")
        expectMoveRemote = None
        super.handleAcceptMessage(connection, ref, remotePeer)
      }
      case _ => super.handleAcceptMessage(connection, remote, remotePeer)
    }
  }

  override protected def checkConstraints(peer: Peer.Signature, count: Int): Boolean =
    ignoreViolationsFor match {
      case Some(s) if s == peer =>
        ignoreViolationsFor = None
        true
      case _ => super.checkConstraints(peer, count)
    }
}
