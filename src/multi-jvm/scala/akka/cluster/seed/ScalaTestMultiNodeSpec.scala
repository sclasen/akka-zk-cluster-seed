package akka.cluster.seed.zookeeper

import akka.remote.testkit.MultiNodeSpecCallbacks
import org.scalatest._

import scala.language.postfixOps

trait ScalaTestMultiNodeSpec extends MultiNodeSpecCallbacks with WordSpecLike with MustMatchers with BeforeAndAfterAll {

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()

}
