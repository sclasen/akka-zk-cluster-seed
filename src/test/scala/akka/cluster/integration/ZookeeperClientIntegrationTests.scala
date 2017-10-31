package akka.cluster.integration

import akka.actor.{ActorPath, ActorSystem}
import akka.cluster.client.ZookeeperClusterClientSettings
import akka.cluster.seed.ZookeeperClusterSeed
import akka.testkit.TestKit
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}


class ZookeeperClientIntegrationTests extends TestKit(
  ActorSystem("test", ZookeeperClientIntegrationSettingsSpec.config(ZookeeperHelper.server.getConnectString)))
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  import ZookeeperHelper._

  "ZookeeperClientIntegrationTests " should {
    "register in zookeper" in {
      // given
      ZookeeperClusterSeed(system).join()

      // when
      val clusterSettings = ZookeeperClusterClientSettings(system)

      // then
      clusterSettings.initialContacts shouldBe Set(ActorPath.fromString(s"${selfAddress(system)}/system/receptionist"))
    }
  }

  override protected def afterAll(): Unit = {
    shutdown()
    stopZK()
  }
}

object ZookeeperClientIntegrationSettingsSpec {
  // these settings will be used for both cluster and client
  def config(zkUrl: String): Config = ConfigFactory.parseString(s"""
         akka{
            actor {
              provider = "akka.cluster.ClusterActorRefProvider"
            }
            cluster {
              seed.zookeeper {
                url = "${zkUrl}"
                path = "/akka/cluster/seed"
              }

              client.zookeeper {
                url = "${zkUrl}"
                path = "/akka/cluster/seed"
                name = "test"
              }
            }
         }
       """)
}