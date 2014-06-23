package akka.cluster.seed

import akka.actor._
import akka.cluster.Cluster
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.framework.recipes.leader.LeaderLatch
import scala.collection.immutable
import org.apache.zookeeper.KeeperException.NodeExistsException
import concurrent.duration._
import concurrent.Await
import collection.JavaConverters._

object ZookeeperClusterSeed extends ExtensionId[ZookeeperClusterSeed] with ExtensionIdProvider {

  override def get(system: ActorSystem): ZookeeperClusterSeed = super.get(system)

  override def createExtension(system: ExtendedActorSystem): ZookeeperClusterSeed = new ZookeeperClusterSeed(system)

  override def lookup() = ZookeeperClusterSeed
}

class ZookeeperClusterSeed(system: ExtendedActorSystem) extends Extension {

  val settings = new ZookeeperClusterSeedSettings(system)

  val address = Cluster(system).selfAddress

  private val client = {
    val retryPolicy = new ExponentialBackoffRetry(1000, 3)
    val client = CuratorFrameworkFactory.newClient(settings.ZKUrl, retryPolicy)
    client.start()
    client
  }

  val myId = address.hostPort

  val path = s"${settings.ZKPath}/${system.name}"

  private val latch = new LeaderLatch(client, path, myId)

  system.registerOnTermination {
    import scala.util.control.Exception._
    ignoring(classOf[IllegalStateException]) { latch.close() }
    ignoring(classOf[IllegalStateException]) { client.close() }
  }

  def join(): Unit = {
    createPathIfNeeded()
    latch.start()
    while (!tryJoin()) {
      system.log.error("component=zookeeper-cluster-seed at=try-join-failed id={}", myId)
      Thread.sleep(1000)
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
        node => AddressFromURIString(s"akka.tcp://${node.getId}")
      }.toList
      system.log.warning("component=zookeeper-cluster-seed at=join-cluster seeds={}", seeds)
      Cluster(system).joinSeedNodes(immutable.Seq(seeds: _*))
      true
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