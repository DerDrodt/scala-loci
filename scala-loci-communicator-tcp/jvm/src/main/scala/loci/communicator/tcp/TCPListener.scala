package loci
package communicator
package tcp

import java.io.IOException
import java.net.{InetAddress, ServerSocket, SocketException}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

class TCPListener(
  val port: Int, interface: String, properties: TCP.Properties)
    extends Listener[TCP] {

  protected def startListening(connectionEstablished: Connected[TCP]): Try[Listening] =
    try {
      val running = new AtomicBoolean(true)
      val socket = new ServerSocket(port, 0, InetAddress.getByName(interface))
      val executor = Executors.newCachedThreadPool()

      def terminate() = {
        try socket.close()
        catch { case _: IOException => }
        executor.shutdown()
      }

      new Thread() {
        override def run() =
          try
            while (true) {
              val connection = socket.accept()
              if (connection != null)
                executor.execute(new Runnable {
                  def run() = TCPHandler.handleConnection(
                    connection, properties, TCPListener.this, { connection =>
                      connectionEstablished.fire(Success(connection))
                    })
                })
            }
          catch {
            case exception: SocketException =>
              if (running.getAndSet(false)) {
                terminate()
                connectionEstablished.fire(Failure(exception))
              }
          }
      }.start()

      Success(new Listening {
        def stopListening(): Unit =
          if (running.getAndSet(false))
            terminate()
      })
    }
    catch {
      case NonFatal(exception) =>
        Failure(exception)
    }
}
