package smoke.mongrel2

import com.typesafe.config.Config
import akka.actor._
import akka.dispatch.{ Future, Promise }
import akka.zeromq.ZeroMQExtension
import akka.zeromq.{ Connect, Frame, Listener, SocketType, ZMQMessage }

import smoke._

class Mongrel2Server(implicit val config: Config, system: ActorSystem) extends Server {
  val recvAddress = config.getString("smoke.mongrel2.recvAddress")
  val sendAddress = config.getString("smoke.mongrel2.sendAddress")
  
  private var _application: (Request) => Future[Response] = _
  private var handlerOption: Option[ActorRef] = None
  
  def setApplication(application: (Request) => Future[Response]) {
    _application = application
  }
  
  def start() {
    if (handlerOption.isEmpty) {
      val handlerProps = Props(new Mongrel2Handler(recvAddress, sendAddress))
      handlerOption = Some(system.actorOf(handlerProps))
      handlerOption map (_ ! SetApplication(_application))
    
      println("Receiving requests on " + recvAddress)
      println("Sending responses on " + sendAddress)
    }
  }
  
  def stop() {
    handlerOption map { handler => 
      handler ! PoisonPill
      println("No longer resonding to Mongrel2 requests.")
    }
  }
  
  case class SetApplication(application: (Request) => Future[Response])

  class Mongrel2Handler(receiveAddress: String, sendAddress: String) 
    extends Actor {
      
    import context.dispatcher
    val system = ZeroMQExtension(context.system)
    
    val pullSocket = system.newSocket(SocketType.Pull, Connect(receiveAddress), Listener(self))
    val pubSocket = system.newSocket(SocketType.Pub, Connect(sendAddress))

    var application: (Request) => Future[Response] = { request =>
      Promise.successful(Response(ServiceUnavailable))
    }

    def send(request: Mongrel2Request, response: Response) = {
      val (sender, connection) = (request.sender, request.connection)
      val header = sender + " " + connection.length + ":" + connection + ","
      pubSocket ! ZMQMessage(Seq(Frame(header + " " + response.toMessage)))
      pubSocket ! ZMQMessage(Seq(Frame(header + " ")))
    }

    def receive = {
      case m: ZMQMessage => 
        try {
          val request = Mongrel2Request(m.payload(0))
          application(request) map { response =>
            send(request, response) 
            log(request, response)
          }
        } catch {
          case _ => 
        }

      case SetApplication(newApplication) => application = newApplication
    }  
  }
}
