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


trait ScalaTestMultiNodeSpec extends MultiNodeSpecCallbacks with WordSpecLike with MustMatchers with BeforeAndAfterAll {

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()

}

object ZookeeperClusterSeedrMultiNodeConfig extends MultiNodeConfig {
  val node1 = role("node1")
  val node2 = role("node2")
  val node3 = role("node3")
  val node4 = role("node4")

  commonConfig(ConfigFactory.parseString(s"""
    akka.cluster.seed.zookeeper.url = "${Properties.envOrElse("ZK_URL", "127.0.0.1:2181")}"
    akka.loglevel = ${Properties.envOrElse("LOG_LEVEL", "INFO")}
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.loggers = ["akka.event.slf4j.Slf4jLogger"]
    akka.remote.log-remote-lifecycle-events = off
    akka.log-dead-letters-during-shutdown = false
    # don't use sigar for tests, native lib not in path
    akka.cluster.metrics.collector-class = akka.cluster.JmxMetricsCollector
                                         """))
}

class ZookeeperClusterSeedMultiJvmNode1 extends ZookeeperClusterSeedSpec

class ZookeeperClusterSeedMultiJvmNode2 extends ZookeeperClusterSeedSpec

class ZookeeperClusterSeedMultiJvmNode3 extends ZookeeperClusterSeedSpec

class ZookeeperClusterSeedMultiJvmNode4 extends ZookeeperClusterSeedSpec

class ZookeeperClusterSeedSpec extends  MultiNodeSpec(ZookeeperClusterSeedrMultiNodeConfig)
with ScalaTestMultiNodeSpec with ImplicitSender {

  import ZookeeperClusterSeedrMultiNodeConfig._

  def initialParticipants = roles.size

  "ZookeeperClusterSeed extension" must {

    "bootstrap a cluster properly" in {
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