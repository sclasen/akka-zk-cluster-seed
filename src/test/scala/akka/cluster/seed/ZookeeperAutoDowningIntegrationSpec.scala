package akka.cluster.seed

import akka.actor.ActorSystem
import akka.cluster.integration.ZookeeperHelper
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.Await
import scala.concurrent.duration._

class ZookeeperAutoDowningIntegrationSpec extends WordSpec with Matchers with Eventually {

  import ZookeeperHelper._

  implicit override val patienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(1, Seconds)))

  def findLeader(seeds: List[(ZookeeperClusterSeed, ActorSystem)]): (ZookeeperClusterSeed, ActorSystem) =
    seeds.find(_._1.isLeader()).get

  "ZookeeperAutoDowning" should {
    "auto down when leader killed" in {
      // given
      val system1 = ActorSystem("system-leader",
        ZookeeperAutoDowningIntegrationSettingsSpec.config(server.getConnectString, 2253))
      val zkS1 = ZookeeperClusterSeed(system1)
      zkS1.join()

      val system2 = ActorSystem("system-leader",
        ZookeeperAutoDowningIntegrationSettingsSpec.config(server.getConnectString, 2554))
      val zkS2 = ZookeeperClusterSeed(system2)
      zkS2.join()

      val system3 = ActorSystem("system-leader",
        ZookeeperAutoDowningIntegrationSettingsSpec.config(server.getConnectString, 2555))
      val zkS3 = ZookeeperClusterSeed(system3)
      zkS3.join()

      val allZks = List((zkS1, system1), (zkS2, system2), (zkS3, system3))

      // expect
      val leader = findLeader(allZks)
      val nonLeader = allZks.filter(_ != leader).head

      Await.result(leader._2.terminate(), 10 seconds)
      nonLeader._1.clusterSystem.state.members.find(_.address == leader._1.clusterSystem.selfAddress) shouldBe defined

      eventually {
        nonLeader._1.clusterSystem.state.members.find(_.address == leader._1.clusterSystem.selfAddress) shouldBe None
      }
    }

    "auto down when non-leader killed" in {
      // given
      val system1 = ActorSystem("system-nonleader",
        ZookeeperAutoDowningIntegrationSettingsSpec.config(server.getConnectString, 2256))
      val zkS1 = ZookeeperClusterSeed(system1)
      zkS1.join()

      val system2 = ActorSystem("system-nonleader",
        ZookeeperAutoDowningIntegrationSettingsSpec.config(server.getConnectString, 2557))
      val zkS2 = ZookeeperClusterSeed(system2)
      zkS2.join()

      val system3 = ActorSystem("system-nonleader",
        ZookeeperAutoDowningIntegrationSettingsSpec.config(server.getConnectString, 2558))
      val zkS3 = ZookeeperClusterSeed(system3)
      zkS3.join()

      val allZks = List((zkS1, system1), (zkS2, system2), (zkS3, system3))

      // expect
      val leader = findLeader(allZks)
      val nonLeader = allZks.filter(_ != leader).head

      Await.result(nonLeader._2.terminate(), 10 seconds)
      leader._1.clusterSystem.state.members.find(_.address == nonLeader._1.clusterSystem.selfAddress) shouldBe defined

      eventually {
        leader._1.clusterSystem.state.members.find(_.address == nonLeader._1.clusterSystem.selfAddress) shouldBe None
      }
    }
  }

}

object ZookeeperAutoDowningIntegrationSettingsSpec {
  def config(zkUrl: String, port: Int): Config = ConfigFactory.parseString(
    s"""
         akka{
            actor {
              provider = "akka.cluster.ClusterActorRefProvider"
            }
            cluster.seed.zookeeper {
              url = "${zkUrl}"
              path = "/akka/cluster/seed"
              auto-down {
                  enabled = true
              }
            }
            remote {
              netty.tcp {
                port = ${port}
              }
            }
         }
       """)
}