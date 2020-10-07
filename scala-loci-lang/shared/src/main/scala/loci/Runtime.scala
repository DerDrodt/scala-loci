package loci

import loci.runtime.RemoteConnections
import loci.runtime.System

import scala.concurrent.Awaitable

abstract class Runtime[P] private[loci] extends Awaitable[Unit] {
  val started: Notice.Stream[Instance[P]]
  val instance: Notice.Steady[Instance[P]]
  val remoteConnections: RemoteConnections
  var runtimeSystem: System
  def instances: Seq[Instance[P]]

  def terminate(): Unit
  val terminated: Notice.Steady[Unit]
}
