package akka.cluster.seed

import java.security.cert.X509Certificate
import javax.net.ssl.{SSLContext, X509TrustManager}
import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.util.{ByteString, Timeout}
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import spray.json.DefaultJsonProtocol._
import spray.json.JsonParser
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

case class ExhibitorClient(system: ActorSystem, exhibitorUrl: String, requestPath: String, validateCerts: Boolean) extends Client {

  val uri = Uri(exhibitorUrl)

  implicit val dispatcher: ExecutionContextExecutor = system.dispatcher

  def getZookeepers(chroot: Option[String] = None): Future[String] = pipeline(HttpRequest(HttpMethods.GET, uri.withPath(Path(requestPath))))(extractUrl).map {
    url => chroot.map(url + "/" + _).getOrElse(url)
  }

  private[this] def extractUrl(resp: HttpResponse): Future[String] = {
    if (resp.status != StatusCodes.OK) throw new RuntimeException(s"${resp.status} while querying exhibitor")
    resp.entity.dataBytes.runFold(ByteString.empty)(_ ++ _)(materializer).map(_.utf8String).map {
      body =>
      val json = JsonParser(body)
      val servers = json.asJsObject.fields("servers").convertTo[List[String]]
      val port = json.asJsObject.fields("port").convertTo[Int]
      servers.map(_ + s":$port").reduceLeft(_ + "," + _)
    }
  }

}

trait Client {

  def system: ActorSystem

  def uri: Uri

  def validateCerts: Boolean

  protected implicit def dispatcher: ExecutionContext

  protected implicit val s: ActorSystem = system

  protected implicit val materializer: ActorMaterializer = ActorMaterializer.create(system)

  implicit val t = Timeout(10 seconds)

  def pipeline[T](req: HttpRequest)(t: HttpResponse => Future[T]): Future[T] = {
    val connectionContext = if(validateCerts){
      Http().defaultClientHttpsContext
    } else {
      val badSslConfig = AkkaSSLConfig().mapSettings {
        s => s.withLoose(s.loose.withDisableSNI(true).withAcceptAnyCertificate(true).withDisableHostnameVerification(true))
      }
      new HttpsConnectionContext(SSL.nonValidatingContext, Some(badSslConfig))
    }

    Http().singleRequest(req, connectionContext).flatMap(t)
  }

}

object SSL {

  lazy val nonValidatingContext: SSLContext = {
    class IgnoreX509TrustManager extends X509TrustManager {
      def checkClientTrusted(chain: Array[X509Certificate], authType: String):Unit = {}

      def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = {}

      def getAcceptedIssuers = null
    }

    val context = SSLContext.getInstance("TLS")
    context.init(null, Array(new IgnoreX509TrustManager), null)
    context
  }
}