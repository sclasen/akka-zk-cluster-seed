package akka.cluster

import akka.actor.ActorSystem
import akka.cluster.seed.ExhibitorClient
import com.typesafe.config.Config

import scala.concurrent.Await
import concurrent.duration._

class ZookeeperClusterSeedSettings(system: ActorSystem,
                                   settingsRoot: String = "akka.cluster.seed.zookeeper",
                                   overwrittenActorSettings: Option[Config] = None) {

  private val zc = overwrittenActorSettings.getOrElse(system.settings.config).getConfig(settingsRoot)

  val ZKUrl: String = if (zc.hasPath("exhibitor.url")) {
    val validate = zc.getBoolean("exhibitor.validate-certs")
    val exhibitorUrl = zc.getString("exhibitor.url")
    val exhibitorPath = if (zc.hasPath("exhibitor.request-path")) zc.getString("exhibitor.request-path") else "/exhibitor/v1/cluster/list"
    Await.result(ExhibitorClient(system, exhibitorUrl, exhibitorPath, validate).getZookeepers(), 10.seconds)
  } else zc.getString("url")

  val ZKPath: String = zc.getString("path")

  val ZKAuthorization: Option[(String, String)] = if (zc.hasPath("authorization.scheme") && zc.hasPath("authorization.auth"))
    Some((zc.getString("authorization.scheme"), zc.getString("authorization.auth")))
  else None

  val host: Option[String] = if (zc.hasPath("host_env_var"))
    Some(zc.getString("host_env_var"))
  else None

  val port: Option[Int] = if (zc.hasPath("port_env_var"))
    Some(zc.getInt("port_env_var"))
  else None

}