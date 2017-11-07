package akka.cluster.client

import akka.actor.{ActorSystem, Props}
import akka.cluster.{AkkaCuratorClient, ZookeeperClusterSeedSettings}
import com.typesafe.config.{Config, ConfigValueFactory}
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.{LockInternals, LockInternalsSorter, StandardLockInternalsDriver}

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.util.Try

object ZookeeperClusterClientSettings {

  private val sorter = new LockInternalsSorter() {
    override def fixForSorting(str: String, lockName: String): String =
      StandardLockInternalsDriver.standardFixForSorting(str, lockName)
  }

  def apply(system: ActorSystem, overwrittenActorSettings: Option[Config] = None): ClusterClientSettings = {
    val config = overwrittenActorSettings.getOrElse(system.settings.config).getConfig("akka.cluster.client")

    val systemName = config.getString("zookeeper.name")

    val receptionistPath = Try(config.getString("zookeeper.receptionistName")).getOrElse("/system/receptionist")

    val settings = new ZookeeperClusterSeedSettings(system, "akka.cluster.client.zookeeper", overwrittenActorSettings)

    val client = AkkaCuratorClient(settings)

    val contacts = getClusterParticipants(client, settings.ZKPath + "/" + systemName).map(_ + receptionistPath)

    system.log.info("component=zookeeper-cluster-client at=find-initial-contacts contacts={}", contacts)

    client.close()

    ClusterClientSettings(
      config.withValue(
        "initial-contacts",
        ConfigValueFactory.fromIterable(immutable.List(contacts: _*).asJava)
      )
    )
  }

  private def getClusterParticipants(client: CuratorFramework, zkPath: String): Seq[String] =  {
    val participants = LockInternals.getParticipantNodes(client,
      zkPath,
      "latch-" /* magic string from LeaderLatch.LOCK_NAME */,
      sorter).asScala

    participants.map(path => new String(client.getData.forPath(path))).toSeq
  }

}

object ZookeeperClusterClientProps {
  def apply(system: ActorSystem): Props = ClusterClient.props(ZookeeperClusterClientSettings(system))
}
