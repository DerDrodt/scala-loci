package loci.runtime

import loci.{MessageBuffer, logging}
import loci.messaging.{ConnectionsBase, Message}
import loci.communicator.{Connection, Connector, Listener, ProtocolCommon}

import scala.util.{Failure, Success, Try}

class MovableRemoteConnections(peer: Peer.Signature, ties: Map[Peer.Signature, Peer.Tie]) extends RemoteConnections(peer, ties) {
  private var expectMoveRemote: Option[(Remote.Reference, Boolean)] = None

  override protected def deserializeMessage(message: MessageBuffer) = {
    val result = Message.deserialize[Method](message)
    result.failed foreach {
      logging.warn("could not parse message", _)
    }
    result
  }

  def expectMove(ref: Remote.Reference): Try[Unit] = {
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
    expectMoveRemote = Some(ref, listen)
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
      val (remote, listen) = expectMoveRemote.get
      if (listen) {
        expectMoveRemote = None
        return super.handleRequestMessage(connection, remotePeer, createDesignatedInstance, listener, Some(remote))
      }
    }
    super.handleRequestMessage(connection, remotePeer, createDesignatedInstance, listener, remoteRef)
  }

  // Case 2
  override protected def handleAcceptMessage(
                                     connection: Connection[ConnectionsBase.Protocol],
                                     remote: Remote.Reference)
  : PartialFunction[Message[Method], Try[Remote.Reference]] = {
    expectMoveRemote match {
      case Some((remote, false)) => {
        // The only issue here as far as I can see would be that there is a new Remote.Reference that was created,
        // but is unused. Maybe that breaks some assumptions?
        expectMoveRemote = None
        super.handleAcceptMessage(connection, remote)
      }
      case None => super.handleAcceptMessage(connection, remote)
    }
  }
}
