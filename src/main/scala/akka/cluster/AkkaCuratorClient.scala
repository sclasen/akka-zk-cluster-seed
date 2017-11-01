package akka.cluster

import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry

object AkkaCuratorClient {
  def apply(settings: ZookeeperClusterSeedSettings) : CuratorFramework = {
    val retryPolicy = new ExponentialBackoffRetry(1000, 3)
    val connStr = settings.ZKUrl.replace("zk://", "")
    val curatorBuilder = CuratorFrameworkFactory.builder()
      .connectString(connStr)
      .retryPolicy(retryPolicy)

    settings.ZKAuthorization match {
      case Some((scheme, auth)) => curatorBuilder.authorization(scheme, auth.getBytes)
      case None =>
    }

    val client = curatorBuilder.build()

    client.start()
    client
  }
}
