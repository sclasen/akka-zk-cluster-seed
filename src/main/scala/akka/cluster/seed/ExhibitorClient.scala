package akka.cluster.seed

import scala.language.postfixOps
import akka.io.IO
import akka.pattern._
import spray.can.Http
import spray.http._
import spray.http.HttpMethods._
import akka.actor.ActorSystem
import akka.util.Timeout
import javax.net.ssl.{ X509TrustManager, SSLContext }
import spray.io.ClientSSLEngineProvider
import java.net.URL
import spray.can.Http.HostConnectorSetup
import spray.http.HttpHeaders._
import spray.http.StatusCodes._
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import spray.client.pipelining._
import spray.json._
import spray.json.DefaultJsonProtocol
import DefaultJsonProtocol._
import spray.json.JsonParser
import java.security.cert.X509Certificate

case class ExhibitorClient(system: ActorSystem, exhibitorUrl: String, validateCerts: Boolean) extends Client {

  val url = new URL(exhibitorUrl)

  implicit val dispatcher = system.dispatcher

  def getZookeepers(chroot: Option[String] = None) = pipeline(HttpRequest(GET, "/exhibitor/v1/cluster/list"))(extractUrl).map {
    url => chroot.map(url + "/" + _).getOrElse(url)
  }

  def extractUrl(resp: HttpResponse): String = {
    if (resp.status != OK) throw new RuntimeException(s"${resp.status} while querying exhibitor")
    val json = JsonParser(resp.entity.asString)
    val servers = json.asJsObject.fields("servers").convertTo[List[String]]
    val port = json.asJsObject.fields("port").convertTo[Int]
    servers.map(_ + s":$port").reduceLeft(_ + "," + _)
  }

}

trait Client {

  def system: ActorSystem

  def url: URL

  def validateCerts: Boolean

  implicit def dispatcher: ExecutionContext

  lazy val creds = url.getUserInfo.split(':')

  implicit val s = system

  implicit val trustfulSslContext: SSLContext = if (validateCerts) implicitly[SSLContext] else SSL.nonValidatingContext

  implicit val clientSSLEngineProvider = if (validateCerts) implicitly[ClientSSLEngineProvider] else SSL.nonValidatingProvider

  implicit val t = Timeout(10 seconds)

  def pipeline[T](req: HttpRequest)(t: HttpResponse => T): Future[T] = connection.flatMap(_.apply(creds(req))).map(t)

  def creds(req: HttpRequest): HttpRequest = req.copy(headers = req.headers ++ List(Authorization(BasicHttpCredentials(creds(0), creds(1)))))

  def connection: Future[SendReceive] = {
    (IO(Http) ? HostConnectorSetup(host = url.getHost, port = 443, sslEncryption = true)).map {
      case Http.HostConnectorInfo(hostConnector, _) => sendReceive(hostConnector)
    }
  }

}

object SSL {

  lazy val nonValidatingContext = {
    class IgnoreX509TrustManager extends X509TrustManager {
      def checkClientTrusted(chain: Array[X509Certificate], authType: String) {}

      def checkServerTrusted(chain: Array[X509Certificate], authType: String) {}

      def getAcceptedIssuers = null
    }

    val context = SSLContext.getInstance("TLS")
    context.init(null, Array(new IgnoreX509TrustManager), null)
    context
  }

  lazy val nonValidatingProvider = ClientSSLEngineProvider {
    _ =>
      val engine = nonValidatingContext.createSSLEngine()
      engine.setUseClientMode(true)
      engine
  }
}