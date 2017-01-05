package akka.cluster.seed

import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.{SSLContext, X509TrustManager}

import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.util.{ByteString, Timeout}
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import spray.json.DefaultJsonProtocol._
import spray.json.JsonParser

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

case class ExhibitorClient(system: ActorSystem, exhibitorUrl: String, requestPath: String, validateCerts: Boolean) extends Client {

  val uri = Uri(exhibitorUrl)

  implicit val dispatcher = system.dispatcher

  def getZookeepers(chroot: Option[String] = None) = pipeline(HttpRequest(HttpMethods.GET, requestPath))(extractUrl).map {
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

  protected implicit val s = system

  protected implicit val materializer = ActorMaterializer.create(system)

  implicit val t = Timeout(10 seconds)

  def pipeline[T](req: HttpRequest)(t: HttpResponse => Future[T]): Future[T] = {
    val connectionContext = if(validateCerts){
      Http().createClientHttpsContext(AkkaSSLConfig())
    } else {
      val badSslConfig = AkkaSSLConfig().mapSettings {
        s => s.withLoose(s.loose.withDisableSNI(true).withAcceptAnyCertificate(true).withDisableHostnameVerification(true))
      }
      Http().createClientHttpsContext(badSslConfig)
    }

    Http().singleRequest(req.copy(uri = uri), connectionContext).flatMap(t)
  }

}
