package akka.cluster.seed

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberUp}


class ZookeeperClusterSeedSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll{


  def this() = this(ActorSystem("akka-zk-seed-it"))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }


  "Zookeeper Cluster Seed Extension" must {
    "join the cluster when alone" in {
      val cluster = Cluster(system)
      cluster.subscribe(testActor, classOf[MemberUp])
      ZookeeperClusterSeed(system).join()
      expectMsgClass(classOf[CurrentClusterState])
      expectMsgClass(classOf[MemberUp])
    }
  }

}
