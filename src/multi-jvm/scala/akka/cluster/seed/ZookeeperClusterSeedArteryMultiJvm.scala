package akka.cluster.seed.zookeeper

import akka.remote.testkit.MultiNodeSpecCallbacks
import akka.util.Timeout
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec}
import akka.testkit.ImplicitSender
import akka.actor._
import com.typesafe.config.{ConfigFactory, Config}
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}
import akka.cluster.Cluster
import scala.language.postfixOps
import scala.util.{Random, Properties}
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.seed.ZookeeperClusterSeed
import concurrent.duration._
import org.scalatest._


object ZookeeperClusterSeedArteryMultiNodeConfig extends MultiNodeConfig {
  val node1 = role("node1")
  val node2 = role("node2")
  val node3 = role("node3")
  val node4 = role("node4")

  commonConfig(ConfigFactory.parseString(s"""
    akka.cluster.seed.zookeeper.url = "${Properties.envOrElse("ZK_URL", "127.0.0.1:2181")}"
    akka.cluster.seed.zookeeper.path = "/akka/cluster/arteryseed"
    akka.loglevel = ${Properties.envOrElse("LOG_LEVEL", "INFO")}
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.loggers = ["akka.event.slf4j.Slf4jLogger"]
    akka.remote.log-remote-lifecycle-events = off
    akka.remote.artery.enabled = on
    akka.log-dead-letters-during-shutdown = false
    # don't use sigar for tests, native lib not in path
    akka.cluster.metrics.collector-class = akka.cluster.JmxMetricsCollector
                                         """))
}

class ZookeeperClusterSeedArteryMultiJvmNode1 extends ZookeeperClusterSeedArterySpec

class ZookeeperClusterSeedArteryMultiJvmNode2 extends ZookeeperClusterSeedArterySpec

class ZookeeperClusterSeedArteryMultiJvmNode3 extends ZookeeperClusterSeedArterySpec

class ZookeeperClusterSeedArteryMultiJvmNode4 extends ZookeeperClusterSeedArterySpec

class ZookeeperClusterSeedArterySpec extends  MultiNodeSpec(ZookeeperClusterSeedArteryMultiNodeConfig)
with ScalaTestMultiNodeSpec with ImplicitSender {

  import ZookeeperClusterSeedArteryMultiNodeConfig._

  def initialParticipants = roles.size

  "ZookeeperClusterSeed extension" must {

    "bootstrap a Artery enabled cluster properly" in {
      Cluster(system).subscribe(testActor, classOf[MemberUp])
      expectMsgClass(classOf[CurrentClusterState])
      Thread.sleep(Random.nextInt(1000))  //add some randomenss to when the joins happen
      ZookeeperClusterSeed(system).join()
      expectMsgClass(10 seconds, classOf[MemberUp])
      expectMsgClass(classOf[MemberUp])
      expectMsgClass(classOf[MemberUp])
      expectMsgClass(classOf[MemberUp])
      enterBarrier("up")
      Cluster(system).readView.members.size must be(4)
      enterBarrier("done")

    }
  }

}