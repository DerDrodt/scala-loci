package loci.runtime
import loci.{MessageBuffer, logging}
import loci.messaging.Message

class MovableRemoteConnections(peer: Peer.Signature, ties: Map[Peer.Signature, Peer.Tie]) extends RemoteConnections(peer, ties) {
  protected def deserializeMessage(message: MessageBuffer) = {
    val result = Message.deserialize[Method](message)
    result.failed foreach {
      logging.warn("could not parse message", _)
    }
    result
  }

  
}
