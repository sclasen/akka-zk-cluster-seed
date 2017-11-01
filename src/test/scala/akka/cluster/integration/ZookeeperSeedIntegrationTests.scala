package akka.cluster.integration

import akka.actor.ActorSystem
import akka.cluster.seed.ZookeeperClusterSeed
import akka.testkit.TestKit
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

class ZookeeperSeedIntegrationTests extends TestKit(
  ActorSystem("test", ZookeeperSeedIntegrationSettingsSpec.config(ZookeeperHelper.server.getConnectString)))
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  import ZookeeperHelper._

  "ZookeeperSeedIntegrationTests " should {
    "register in zookeper" in {
      // given
      ZookeeperClusterSeed(system).join()

      val registerPath = "/akka/cluster/seed/test"

      // expect
      val registeredNodes = getChildren(registerPath)
      registeredNodes should have size 1

      readPath(registerPath + "/" + registeredNodes.head) shouldBe selfAddress(system)
    }
  }

  override protected def afterAll(): Unit = {
    shutdown()
    stopZK()
  }
}

object ZookeeperSeedIntegrationSettingsSpec {
  def config(zkUrl: String): Config = ConfigFactory.parseString(s"""
         akka{
            actor {
              provider = "akka.cluster.ClusterActorRefProvider"
            }
            cluster.seed.zookeeper {
              url = "${zkUrl}"
              path = "/akka/cluster/seed"
            }
         }
       """)
}