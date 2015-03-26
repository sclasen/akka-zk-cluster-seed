package akka.cluster.seed

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.typesafe.config.{ConfigFactory, Config}
import org.scalatest.{Matchers, WordSpecLike}

class ZookeeperClusterSeedSettingsSpec extends TestKit(ActorSystem("test", ZookeeperClusterSeedSettingsSpec.config))
  with WordSpecLike with Matchers {

  "ZookeeperClusterSeedSettings " should {
    "parse authentication options from config" in {
      val settings = new ZookeeperClusterSeedSettings(system)
      settings.ZKAuthorization should be(Some("digest", "foo:bar"))
    }
  }
}

object ZookeeperClusterSeedSettingsSpec {
  val config: Config = ConfigFactory.parseString("""
         akka.cluster.seed.zookeeper {
             authorization {
               scheme = "digest"
               auth = "foo:bar"
             }
         }
       """)
}
