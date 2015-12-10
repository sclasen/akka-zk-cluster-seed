package akka.cluster.seed

import language.postfixOps
import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpec}
import com.typesafe.config.ConfigFactory
import concurrent.duration._
import org.scalatest.concurrent.ScalaFutures

class ExhibitorClientSpec extends WordSpec with MustMatchers with BeforeAndAfterAll with ScalaFutures {


  override implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = 5 seconds)

  var system:ActorSystem = _


  "ExhibitorClient" must {
    "read the zookeepers from exhibitor" in {
      val zks = ExhibitorClient(system, sys.env("EXHIBITOR_URL"), "/exhibitor/v1/cluster/list", false).getZookeepers(Some("chroot")).futureValue
      zks must endWith("/chroot")
      zks must not startWith("/chroot")
    }
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    system.shutdown()
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    system = ActorSystem("test", ConfigFactory.empty())
  }
}
