package retier

import typeconstraints._

final abstract class Transmission
  [V, T, R <: Peer, L <: Peer, M <: ConnectionMultiplicity]

object Transmission {
  implicit def transmission
    [V, L <: Peer, R <: Peer, T, U, M <: ConnectionMultiplicity]
    (implicit
        transmittable: V <:< (T sharedOn R),
        value: T <:!< (_ <=> _),
        localPeer: LocalPeer[L],
        connection: PeerConnection[L#Connection, R, M]):
    Transmission[V, T, R, L, M] = `#macro`

  implicit def transmissionFromSingle
    [V, L <: Peer, R <: Peer, T, U, M <: ConnectionMultiplicity]
    (implicit
        transmittable: V <:< (T fromSingle R),
        value: T <:!< (_ <=> _),
        localPeer: LocalPeer[L],
        connection: PeerConnection[L#Connection, R, M]):
    Transmission[V, T, R, L, OptionalConnection] = `#macro`

  implicit def transmissionFromMultiple
    [V, L <: Peer, R <: Peer, T, U, M <: ConnectionMultiplicity]
    (implicit
        transmittable: V <:< (T fromMultiple R),
        value: T <:!< (_ <=> _),
        localPeer: LocalPeer[L],
        connection: PeerConnection[L#Connection, R, M]):
    Transmission[V, T, R, L, MultipleConnection] = `#macro`

  implicit def issuedTransmission
    [V, L <: Peer, R <: Peer, P <: Peer, T, U, M <: ConnectionMultiplicity]
    (implicit
        transmittable: V <:< (Remote[P] <=> T sharedOn R),
        localPeer: LocalPeer[L],
        connection: PeerConnection[L#Connection, R, M],
        dispatched: L <:< P):
    Transmission[V, T, R, L, M] = `#macro`

  implicit def issuedTransmissionFromSingle
    [V, L <: Peer, R <: Peer, P <: Peer, T, U, M <: ConnectionMultiplicity]
    (implicit
        transmittable: V <:< (Remote[P] <=> T fromSingle R),
        localPeer: LocalPeer[L],
        connection: PeerConnection[L#Connection, R, M],
        dispatched: L <:< P):
    Transmission[V, T, R, L, OptionalConnection] = `#macro`

  implicit def issuedTransmissionFromMultiple
    [V, L <: Peer, R <: Peer, P <: Peer, T, U, M <: ConnectionMultiplicity]
    (implicit
        transmittable: V <:< (Remote[P] <=> T fromMultiple R),
        localPeer: LocalPeer[L],
        connection: PeerConnection[L#Connection, R, M],
        dispatched: L <:< P):
    Transmission[V, T, R, L, MultipleConnection] = `#macro`
}
