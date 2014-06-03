akka-zk-cluster-seed
====================

Bootstrap your akka cluster with this one simple trick.

`akka-zk-cluster-seed` is an akka extension that will free you from having to manage cluster seed nodes.

Assuming you already have easy access to a zookeeper cluster.

use
---

Add the folowing dependency to your project. `akka-zk-cluster-seed` is built with akka dependencies marked as provided, so it should work with
a reasonable range of akka versions configured in your build.sbt.

```scala
libraryDependencies += "com.sclasen" %% "akka-zk-cluster-seed" % "0.0.3"
```

When starting your app, use the `ZookeeperClusterSeed` extension, instead of the `Cluster` extension to join your cluster.

```scala
val system = ...
ZookeeperClusterSeed(system).join()
```

configure
---------

`akka-zk-cluster-seed` uses the standard akka `reference.conf` configuration defaults mechanism.

You will certainly want to provide an override to the `akka.cluster.seed.zookeeper.url` config property.
It expects standard zookeeper url format like so: `192.168.12.11,192.168.12.12,192.168.12.13:2818`


```
// reference.conf
akka.cluster.seed.zookeeper {
    url = "127.0.0.1:2181"
    path = "/akka/cluster/seed"
}

```

details
-------

`akka-zk-cluster-seed` uses netflix curator zookeeper client and recipes to manage a single, dynamic seed node for your cluster.
It uses the curator `LeaderLatch` to elect a node to serve as a seed node. The lead node `join`s itself to the cluster and all
other nodes `joinSeedNodes` using the current leader as the seed.  If the leader goes offline, a new leader is elected to be the seed.


