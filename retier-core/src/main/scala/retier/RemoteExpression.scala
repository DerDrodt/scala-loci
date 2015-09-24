package retier

import dslparadise._
import scala.language.higherKinds

protected final abstract class RemoteSelectionExpression[P <: Peer] {
  def on[P0 <: P, `P'` <: Peer](peer: Remote[P0])
    (implicit ev: FirstIfNotEmptyElseSecond[P, P0, `P'`]): RemoteExpression[`P'`, fromSingle]
  def on[P0 <: P, `P'` <: Peer](peers: Remote[P0]*)
    (implicit ev: FirstIfNotEmptyElseSecond[P, P0, `P'`]): RemoteExpression[`P'`, fromMultiple]
}

protected final abstract class RemoteExpression[P <: Peer, placed[_, _ <: Peer]]
    extends RemoteExpressionUsing[P, placed] {
  def apply[T, U, L <: Peer](f: CurrentLocalPeerRemoteComputation[P] `implicit =>` T)
    (implicit
        ev0: LocalPeer[L],
        ev1: PlacingTypes[P, T, U],
        ev2: PeerConnection[L#Connection, P, _]): U placed P
  def using[V0, T0](v0: V0)
    (implicit
        ev: ValueTypes[V0, T0]): RemoteUsingExpression[P, placed, T0]
  def call[T, L <: Peer, P0 >: P <: Peer, `P'` <: Peer](method: RemoteMethod[T, P0])
    (implicit
        ev0: LocalPeer[L],
        ev1: FirstIfNotEmptyElseSecond[P, P0, `P'`],
        ev2: PeerConnection[L#Connection, `P'`, _]): T placed `P'`
  def set[T, L <: Peer, P0 >: P <: Peer, `P'` <: Peer](property: RemoteProperty[T, P0])
    (implicit
        ev0: LocalPeer[L],
        ev1: FirstIfNotEmptyElseSecond[P, P0, `P'`],
        ev2: PeerConnection[L#Connection, `P'`, _]): RemoteSettingExpression[T, `P'`, L, placed]
  def issued: RemoteIssuingExpression[P, placed]
}

protected final abstract class RemoteSettingExpression[T, P <: Peer, L <: Peer, placed[_, _ <: Peer]] {
  def :=(v: T localOn L): Unit placed P
}

protected final abstract class RemoteUsingExpression[P <: Peer, placed[_, _ <: Peer], T0] {
  def in[T, U, L <: Peer]
    (f: CurrentLocalPeerRemoteComputation[P] `implicit =>` (
      T0 => T))
    (implicit
        ev0: LocalPeer[L],
        ev1: PlacingTypes[P, T, U],
        ev2: PeerConnection[L#Connection, P, _]): U placed P
}

protected final abstract class RemoteIssuingExpression[P <: Peer, placed[_, _ <: Peer]]
    extends RemoteExpressionIssuedUsing[P, placed] {
  def apply[T, U, I, L <: Peer](f: CurrentLocalPeerRemoteComputation[P] `implicit =>` T)
    (implicit
        ev0: LocalPeer[L],
        ev1: PlacingTypes[P, T, I],
        ev2: IssuingTypes[L, I, U],
        ev3: PeerConnection[L#Connection, P, _],
        ev4: PeerConnection[P#Connection, L, _]): U placed P
  def using[V0, T0](v0: V0)
    (implicit
        ev: ValueTypes[V0, T0]): RemoteIssuedUsingExpression[P, placed, T0]
}

protected final abstract class RemoteIssuedUsingExpression[P <: Peer, placed[_, _ <: Peer], T0] {
  def in[T, U, I, L <: Peer]
    (f: CurrentLocalPeerRemoteComputation[P] `implicit =>` (
      T0 => T))
    (implicit
        ev0: LocalPeer[L],
        ev1: PlacingTypes[P, T, I],
        ev2: IssuingTypes[L, I, U],
        ev3: PeerConnection[L#Connection, P, _],
        ev4: PeerConnection[P#Connection, L, _]): U placed P
}


protected final abstract class FirstIfNotEmptyElseSecond[N, O, T]

protected sealed trait FirstIfNotEmptyElseSecondSecondFallback {
  implicit def nothingOrNotInferred[N, O]:
    FirstIfNotEmptyElseSecond[N, O, O] = `#macro`
}

protected sealed trait FirstIfNotEmptyElseSecondFirstFallback
    extends FirstIfNotEmptyElseSecondSecondFallback {
  implicit def inferred[N, O]
    (implicit ev: N =:= N): FirstIfNotEmptyElseSecond[N, O, N] = `#macro`
}

protected object FirstIfNotEmptyElseSecond
    extends FirstIfNotEmptyElseSecondFirstFallback {
  implicit def nothing[N, O]
    (implicit ev: N =:= Nothing): FirstIfNotEmptyElseSecond[N, O, O] = `#macro`
}
