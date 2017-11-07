package akka.cluster.seed

import java.io.Closeable

import akka.actor._
import akka.cluster.{AkkaCuratorClient, AutoDownUnresolvedStrategies, Cluster, ZookeeperClusterSeedSettings}
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.{PathChildrenCache, PathChildrenCacheEvent, PathChildrenCacheListener}
import org.apache.curator.framework.recipes.leader.{LeaderLatch, LeaderLatchListener}
import org.apache.curator.framework.state.{ConnectionState, ConnectionStateListener}
import org.apache.zookeeper.KeeperException.{NoNodeException, NodeExistsException}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.{immutable, mutable}
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.Exception.ignoring

object ZookeeperClusterSeed extends ExtensionId[ZookeeperClusterSeed] with ExtensionIdProvider {

  override def get(system: ActorSystem): ZookeeperClusterSeed = super.get(system)

  override def createExtension(system: ExtendedActorSystem): ZookeeperClusterSeed = new ZookeeperClusterSeed(system)

  override def lookup() = ZookeeperClusterSeed
}

class ZookeeperClusterSeed(system: ExtendedActorSystem) extends Extension {

  val settings = new ZookeeperClusterSeedSettings(system)

  private[seed] val clusterSystem = Cluster(system)
  val selfAddress: Address = clusterSystem.selfAddress
  val address: Address = if (settings.host.nonEmpty && settings.port.nonEmpty) {
    system.log.info(s"host:port read from environment variables=${settings.host}:${settings.port}")
    selfAddress.copy(host = settings.host, port = settings.port)
  } else
    Cluster(system).selfAddress

  private val client = AkkaCuratorClient(settings)

  val myId = s"${address.protocol}://${address.hostPort}"

  val path = s"${settings.ZKPath}/${system.name}"

  removeEphemeralNodes()

  private val latch = new LeaderLatch(client, path, myId)
  latch.addListener(new LeaderLatchListener {
    override def isLeader: Unit = system.log.info("component=zookeeper-cluster-seed at=leader-change status=Became a leader")

    override def notLeader(): Unit = system.log.info("component=zookeeper-cluster-seed at=leader-change status=Lost leadership")
  })

  if (settings.autoShutdown) {
    client.getConnectionStateListenable.addListener(new ConnectionStateListener {
      override def stateChanged(client: CuratorFramework, newState: ConnectionState): Unit = {
        import ConnectionState._
        newState match {
          case LOST =>
            clusterSystem.leave(clusterSystem.selfAddress)
            system.terminate()
          case _ =>
        }
        system.log.warning("component=zookeeper-cluser-seed at=conntection-state-changed state={}", newState)
      }
    })

    clusterSystem.registerOnMemberRemoved {
      // exit JVM when ActorSystem has been terminated
      system.registerOnTermination(System.exit(0))
      // shut down ActorSystem
      system.terminate()
      system.log.info("Termination started")

      // In case ActorSystem shutdown takes longer than 10 seconds,
      // exit the JVM forcefully anyway.
      // We must spawn a separate thread to not block current thread,
      // since that would have blocked the shutdown of the ActorSystem.
      new Thread {
        override def run(): Unit = {
          if (Try(Await.ready(system.whenTerminated, 10.seconds)).isFailure) {
            system.log.info("Forcing system exit")
            System.exit(-1)
          }
        }
      }.start()
    }
  }

  private var seedEntryAdded = false

  private val closeableServices = mutable.Set[Closeable]()

  closeableServices.add(latch)

  if (settings.autoDown) {

    @tailrec
    def waitForLeaderChange(times: Int, delay: Int)
                           (removedAddress: String): Either[Unit, Unit] =
      latch.getLeader.getId.equals(removedAddress) match {
        case false => Left(null)
        case _ if times > 0 =>
          Thread.sleep(delay)
          waitForLeaderChange(times - 1, delay)(removedAddress)
        case _ => Right(null)
      }

    val pathCache = new PathChildrenCache(client, path, true)
    pathCache.getListenable.addListener(new PathChildrenCacheListener {
      override def childEvent(client: CuratorFramework, event: PathChildrenCacheEvent): Unit = {
        event.getType match {
          case PathChildrenCacheEvent.Type.CHILD_REMOVED =>
            val childAddress = new String(event.getData.getData)

            waitForLeaderChange(settings.autoDownMaxWait.toMillis.toInt / 100, 100)(childAddress) match {
              case Left(_) =>
                if (latch.getLeader.getId.equals(latch.getId)) { // hasLeadership might not work yet, as it is a cached local field
                  system.log.info("component=zookeeper-cluster-seed at=downing-cluster-node node-address={}", childAddress)
                  clusterSystem.down(AddressFromURIString(childAddress))
                } else {
                  system.log.info("component=zookeeper-cluster-seed at=downing-cluster-node status=not a leader state={} leader={}",
                    latch.getState, latch.getLeader)
                }
              case Right(_) => settings.autoDownUnresolvedStrategy match {
                case AutoDownUnresolvedStrategies.Log =>
                  system.log.info("component=zookeeper-cluster-seed at=downing-cluster-node status=leader-change-timeout" +
                    " timed out while waiting for leader to change. Ignoring.")
                case AutoDownUnresolvedStrategies.ForceDown =>
                  system.log.info("component=zookeeper-cluster-seed at=downing-cluster-node status=leader-change-timeout" +
                    " timed out while waiting for leader to change. Forcing down of {}.", childAddress)
                  clusterSystem.down(AddressFromURIString(childAddress))
                case _ => // ignore - validate in ZookeeperClusterSeedSettings
              }
            }
          case _ => // ignore non-child-removed events
        }
      }
    })
    pathCache.start()
    closeableServices.add(pathCache)
  }

  /**
    * Join or create a cluster using Zookeeper to handle
    */
  def join(): Unit = synchronized {
    createPathIfNeeded()
    latch.start()
    seedEntryAdded = true
    while (!tryJoin()) {
      system.log.warning("component=zookeeper-cluster-seed at=try-join-failed id={}", myId)
      Thread.sleep(1000)
    }

    clusterSystem.registerOnMemberRemoved {
      removeSeedEntry()
    }

    system.registerOnTermination {
      ignoring(classOf[IllegalStateException]) {
        client.close()
      }
    }
  }

  def removeSeedEntry(): Unit = synchronized {
    if (seedEntryAdded) {
      ignoring(classOf[IllegalStateException]) {
        closeableServices.foreach(_.close())
        seedEntryAdded = false
      }
    }
  }

  private[seed] def isLeader(): Boolean = latch.hasLeadership

  private def tryJoin(): Boolean = {
    val leadParticipant = latch.getLeader
    if (!leadParticipant.isLeader) false
    else if (leadParticipant.getId == myId) {
      system.log.info("component=zookeeper-cluster-seed at=this-node-is-leader-seed id={}", myId)
      Cluster(system).join(address)
      true
    } else {
      val seeds = latch.getParticipants.iterator().asScala.filterNot(_.getId == myId).map {
        node => AddressFromURIString(node.getId)
      }.toList
      system.log.info("component=zookeeper-cluster-seed at=join-cluster seeds={}", seeds)
      Cluster(system).joinSeedNodes(immutable.Seq(seeds: _*))

      val joined = Promise[Boolean]()

      Cluster(system).registerOnMemberUp {
        joined.trySuccess(true)
      }

      try {
        Await.result(joined.future, 10.seconds)
      } catch {
        case _: TimeoutException => false
      }
    }
  }

  private def createPathIfNeeded() {
    Option(client.checkExists().forPath(path)).getOrElse {
      try {
        client.create().creatingParentsIfNeeded().forPath(path)
      } catch {
        case e: NodeExistsException => system.log.info("component=zookeeper-cluster-seed at=path-create-race-detected")
      }
    }
  }

  /**
    * Removes ephemeral nodes for self address that may exist when node restarts abnormally
    */
  def removeEphemeralNodes(): Unit = {
    val ephemeralNodes = try {
      client.getChildren.forPath(path).asScala
    } catch {
      case _: NoNodeException => Nil
    }

    ephemeralNodes
      .map(p => s"$path/$p")
      .map { p =>
        try {
          (p, client.getData.forPath(p))
        } catch {
          case _: NoNodeException => (p, Array.empty[Byte])
        }
      }
      .filter(pd => new String(pd._2) == myId)
      .foreach {
        case (p, _) =>
          try {
            client.delete.forPath(p)
          } catch {
            case _: NoNodeException => // do nothing
          }
      }
  }
}