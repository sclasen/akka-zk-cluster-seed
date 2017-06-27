package akka.cluster.seed

import akka.actor._
import akka.cluster.Cluster
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.framework.recipes.leader.LeaderLatch

import scala.collection.immutable
import org.apache.zookeeper.KeeperException.{ NoNodeException, NodeExistsException }

import concurrent.duration._
import scala.concurrent._
import collection.JavaConverters._
import scala.util.control.Exception.ignoring

object ZookeeperClusterSeed extends ExtensionId[ZookeeperClusterSeed] with ExtensionIdProvider {

  override def get(system: ActorSystem): ZookeeperClusterSeed = super.get(system)

  override def createExtension(system: ExtendedActorSystem): ZookeeperClusterSeed = new ZookeeperClusterSeed(system)

  override def lookup() = ZookeeperClusterSeed
}

class ZookeeperClusterSeed(system: ExtendedActorSystem) extends Extension {

  val settings = new ZookeeperClusterSeedSettings(system)

  private val clusterSystem = Cluster(system)
  val selfAddress: Address = clusterSystem.selfAddress
  val address    : Address = if (settings.host.nonEmpty && settings.port.nonEmpty) {
    system.log.info(s"host:port read from environment variables=${settings.host}:${settings.port}")
    selfAddress.copy(host = settings.host, port = settings.port)
  } else
    Cluster(system).selfAddress

  private val client = {
    val retryPolicy = new ExponentialBackoffRetry(1000, 3)
    val connStr = settings.ZKUrl.replace("zk://", "")
    val curatorBuilder = CuratorFrameworkFactory.builder()
      .connectString(connStr)
      .retryPolicy(retryPolicy)

    settings.ZKAuthorization match {
      case Some((scheme, auth)) => curatorBuilder.authorization(scheme, auth.getBytes)
      case None =>
    }

    val client = curatorBuilder.build()

    client.start()
    client
  }

  val myId = s"${address.protocol}://${address.hostPort}"

  val path = s"${settings.ZKPath}/${system.name}"

  removeEphemeralNodes()

  private val latch = new LeaderLatch(client, path, myId)
  private var seedEntryAdded = false

  /**
    * Join or create a cluster using Zookeeper to handle
    */
  def join(): Unit = synchronized {
    createPathIfNeeded()
    latch.start()
    seedEntryAdded = true
    while (!tryJoin()) {
      system.log.warning("component=zookeeper-cluster-seed at=try-join-failed id={}", myId)
      Thread.sleep(1000)
    }

    clusterSystem.registerOnMemberRemoved {
      removeSeedEntry()
    }

    system.registerOnTermination {
      ignoring(classOf[IllegalStateException]) {
        client.close()
      }
    }
  }

  def removeSeedEntry(): Unit = synchronized {
    if (seedEntryAdded) {
      ignoring(classOf[IllegalStateException]) {
        latch.close()
        seedEntryAdded = false
      }
    }
  }

  private def tryJoin(): Boolean = {
    val leadParticipant = latch.getLeader
    if (!leadParticipant.isLeader) false
    else if (leadParticipant.getId == myId) {
      system.log.warning("component=zookeeper-cluster-seed at=this-node-is-leader-seed id={}", myId)
      Cluster(system).join(address)
      true
    } else {
      val seeds = latch.getParticipants.iterator().asScala.filterNot(_.getId == myId).map {
        node => AddressFromURIString(node.getId)
      }.toList
      system.log.warning("component=zookeeper-cluster-seed at=join-cluster seeds={}", seeds)
      Cluster(system).joinSeedNodes(immutable.Seq(seeds: _*))

      val joined = Promise[Boolean]()

      Cluster(system).registerOnMemberUp {
        joined.trySuccess(true)
      }

      try {
        Await.result(joined.future, 10.seconds)
      } catch {
        case _: TimeoutException => false
      }
    }
  }

  private def createPathIfNeeded() {
    Option(client.checkExists().forPath(path)).getOrElse {
      try {
        client.create().creatingParentsIfNeeded().forPath(path)
      } catch {
        case e: NodeExistsException => system.log.info("component=zookeeper-cluster-seed at=path-create-race-detected")
      }
    }
  }

  /**
    * Removes ephemeral nodes for self address that may exist when node restarts abnormally
    */
  def removeEphemeralNodes(): Unit = {
    val ephemeralNodes = try {
      client.getChildren.forPath(path).asScala
    } catch {
      case _: NoNodeException => Nil
    }

    ephemeralNodes
      .map(p => s"$path/$p")
      .map { p =>
        try {
          (p, client.getData.forPath(p))
        } catch {
          case _: NoNodeException => (p, Array.empty[Byte])
        }
      }
      .filter(pd => new String(pd._2) == myId)
      .foreach {
        case (p, _) =>
          try {
            client.delete.forPath(p)
          } catch {
            case _: NoNodeException => // do nothing
          }
      }
  }
}

class ZookeeperClusterSeedSettings(system: ActorSystem) {

  private val zc = system.settings.config.getConfig("akka.cluster.seed.zookeeper")

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