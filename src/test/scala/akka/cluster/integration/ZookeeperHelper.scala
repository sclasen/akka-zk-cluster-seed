package akka.cluster.integration

import akka.actor.ActorSystem
import akka.cluster.Cluster
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.RetryOneTime
import org.apache.curator.test.TestingServer

import scala.collection.JavaConverters._

object ZookeeperHelper {

  val zookeepers = new ThreadLocal[TestingServer]

  def server: TestingServer = {
    if (zookeepers.get() == null) startZK()
    zookeepers.get()
  }

  def getChildren(path: String): List[String] = {
    val client = newClient
    val children = client.getChildren.forPath(path)
    client.close()
    children.asScala.toList
  }

  def readPath(path: String): String = {
    println(s"reading path $path")
    val client = newClient
    val data = new String(client.getData.forPath(path))
    client.close()
    data
  }

  def selfAddress(system: ActorSystem): String = Cluster(system).selfAddress.toString

  def stopZK(): Unit = {
    server.close()
    zookeepers.remove()
  }

  def startZK(): Unit = {
    val server = new TestingServer()
    zookeepers.set(server)
  }

  private def newClient: CuratorFramework = {
    val client = CuratorFrameworkFactory.builder()
      .connectString(server.getConnectString)
      .retryPolicy(new RetryOneTime(1000))
      .build()
    client.start()
    client
  }
}