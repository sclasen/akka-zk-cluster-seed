package akka.cluster.seed

import akka.actor._
import akka.cluster.Cluster
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import akka.remote.RemoteActorRefProvider
import org.apache.curator.framework.recipes.leader.LeaderLatch
import scala.collection.immutable
import org.apache.zookeeper.KeeperException.NodeExistsException
import concurrent.duration._
import concurrent.Await

object ZookeeperClusterSeed extends ExtensionId[ZookeeperClusterSeed] with ExtensionIdProvider {

  override def get(system: ActorSystem): ZookeeperClusterSeed = super.get(system)

  override def createExtension(system: ExtendedActorSystem): ZookeeperClusterSeed = new ZookeeperClusterSeed(system)

  override def lookup() = ZookeeperClusterSeed
}

class ZookeeperClusterSeed(system: ExtendedActorSystem) extends Extension {

  val settings = new ZookeeperClusterSeedSettings(system)

  val address = system.provider match {
    case rarp: RemoteActorRefProvider => rarp.transport.defaultAddress
    case _ => system.provider.rootPath.address
  }

  val client = {
    val retryPolicy = new ExponentialBackoffRetry(1000, 3)
    val client = CuratorFrameworkFactory.newClient(settings.ZKUrl, retryPolicy)
    client.start()
    client
  }

  val myId = address.hostPort

  val path = s"${settings.ZKPath}/${system.name}"

  val latch = new LeaderLatch(client, path, myId)

  system.registerOnTermination {
    latch.close()
    client.close()
  }

  def join() = {
    createPathIfNeeded()
    latch.start()
    val leaderId = latch.getLeader.getId
    if (leaderId == myId) {
      system.log.warning("component=zookeeper-cluster-seed at=this-node-is-leader-seed id={}", myId)
      Cluster(system).join(address)
    } else {
      val leader = AddressFromURIString(s"akka.tcp://${leaderId}")
      system.log.warning("component=zookeeper-cluster-seed at=join-cluster leader={}", leader)
      Cluster(system).joinSeedNodes(immutable.Seq(leader))
    }
  }

  def createPathIfNeeded() {
    Option(client.checkExists().forPath(path)).getOrElse {
      try {
        client.create().creatingParentsIfNeeded().forPath(path)
      } catch {
        case e: NodeExistsException => system.log.info("component=zookeeper-cluster-seed at=path-create-race-detected")
      }
    }
  }

}
class ZookeeperClusterSeedSettings(system: ActorSystem) {

  private val zc = system.settings.config.getConfig("akka.cluster.seed.zookeeper")

  val ZKUrl = if (zc.hasPath("exhibitor.url")) {
    val validate = zc.getBoolean("exhibitor.validate-certs")
    val exhibitorUrl = zc.getString("exhibitor.url")
    Await.result(ExhibitorClient(system, exhibitorUrl, validate).getZookeepers(), 10 seconds)
  } else zc.getString("url")

  val ZKPath = zc.getString("path")

}