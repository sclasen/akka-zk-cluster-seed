akka-zk-cluster-seed
====================

Bootstrap your akka cluster with this one simple trick.

`akka-zk-cluster-seed` is an akka extension that will free you from having to manage cluster seed nodes.

Assuming you already have easy access to a zookeeper cluster.

use
---

Add the folowing dependency to your project. `akka-zk-cluster-seed` is built with akka dependencies marked as provided, so it should work with
a reasonable range of akka versions configured in your build.sbt. Note that you must also have `spray 1.3.X and spray-json as dependencies in your
project if you want to use the neftlix exhibitor integration described below.

```scala
libraryDependencies += "com.sclasen" %% "akka-zk-cluster-seed" % "0.1.2"  // If using Akka 2.3.x
libraryDependencies += "com.sclasen" %% "akka-zk-cluster-seed" % "0.1.8"  // If using Akka 2.4.x
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


```yaml
// reference.conf
akka.cluster.seed.zookeeper {
    url = "127.0.0.1:2181"
    path = "/akka/cluster/seed"
}

```

`netfix exhibitor` integration: if you want to use netflix exhibitor to discover your zookeeper servers, you should configure like so.


```yaml
// application.conf
akka.cluster.seed.zookeeper {
    path = "/akka/cluster/seed"
    exhibitor {
        url = "https://user:pass@host:port
        validate-certs = true|false
    }
}

```

If you require a different exhibitor request path than the default `/exhibitor/v1/cluster/list`

```yaml
// application.conf
akka.cluster.seed.zookeeper {
    path = "/akka/cluster/seed"
    exhibitor {
        url = "https://user:pass@host:port
        request-path = "/my/custom/path"
        validate-certs = true|false
    }
}

```

If your zookeeper path requires authorization you have to specify additional `authorization` section:


```yaml
// application.conf
akka.cluster.seed.zookeeper {
    authorization {
        scheme = "digest"
        auth = "username:secret"
    }
}
```

### Auto-downing

Auto-downing of nodes when they're unregistered from zookeeper:

```yaml
// aplication.conf
akka.cluster.seed.zookeeper {
    auto-down {
      enabled = true # default is false - autodawning is switched off
      wait-for-leader = 5 secons # duration from scala.concurrent.duration.Duration, default 5 seconds
      unresolved-strategy = log # can be 'log' or 'force-down', default is 'log'
    }
}
```

When switched on, when a node is unregistered from zookeeper it will be automatically removed from the cluster by the
curator's latch leader (for more info see below in the _details_ section).

In order to prevent from notification race conditions each node upon notification of a node removal will firstly wait
for a new leader election (should the previously leader be removed) and only then the newly elected leader will
down the unregistered node from the cluster.

The two extra options `wait-for-leader` and `unresolved-strategy` are used to control the behaviour of the mentioned wait.
`wait-for-leader` is used to set the maximum time the node will wait for the new leader to get elected, while with 
`unresolved-strategy` you can either choose to log a warning should the process times out or force downing of the removed
node which in that case will be performed from all the notified nodes. 

### Shutdown on zookeeper disconnect

Especially when using zookeeper auto-downing it might be problematic that once a node gets kicked out from the cluster
by other nodes there is no good recovery plans once it reconnects.

You might choose the application to shutdown once that happens. If you are using auto-scaling deployment environments like
DC/OS the container will take care of restarting your app. This might be the best approach as you will not end up with
some weird states preserved within your actors.

```yaml
// aplication.conf
akka.cluster.seed.zookeeper {
    shutdown-on-disconnect = true # false by default
}
```

details
-------

`akka-zk-cluster-seed` uses netflix curator zookeeper client and recipes to manage a single, dynamic seed node for your cluster.
It uses the curator `LeaderLatch` to elect a node to serve as a seed node. The lead node `join`s itself to the cluster and all
other nodes `joinSeedNodes` using the current leader as the seed.  If the leader goes offline, a new leader is elected to be the seed.


If you need to use other hostname & port you may configure the _name_ of the environment variables to retrieve these values from.
This might be handy to use with docker containers


```yaml
// reference.conf
akka.cluster.seed.zookeeper {
    url = "127.0.0.1:2181"
    path = "/akka/cluster/seed"
    host_env_var = ${?HOST}
    port_env_var = ${?PORT_8080}
}
```

client
------

`akka-zk-cluter-seed` can be used to create a `ClusterClient` that will automatically pull `initial-contacts` from Zookeeper.
The configuration is very similar to the seed config

```yaml
// client-reference.conf
akka.clister.client.zookeeper {
    url = "127.0.0.1:2181"
    path = "/akka/cluster/seed"
    name = "myclusteractor" # this is the name of your actor system
    
    receptionistName = "/system/receptionist" # optional, set to '/system/receptionist' by default
    
    // and all the connection properties you can use in your seed config like 'exhibitor' or 'authorization' etc.
}
```

Usage in your code is as simple as

```scala
val clusterClient = system.actorOf(ZookeeperClusterClientProps(system), "clusterClient")

```

where `system` is your `ActorSystem` which uses the above configuration.

If you would like to use multiple cluster clients from one application you can provide all the configuration that is 
common in your case and then provide the needed config in your code for example

Your application.conf file

```yaml
akka.cluster.client {
    zookeeper {
      url = ${ZOOKEEPER_ADDR}
      path = "/akka/cluster/seed"
    }
    establishing-get-contacts-interval = 3s
    refresh-contacts-interval = 60s
    heartbeat-interval = 2s
    acceptable-heartbeat-pause = 5s
    buffer-size = 5000
}
```

Your application bootstrap code (given you have two clusters called `foo` and `bar`)

```scala
// connect to multiple clusters

val zookeeperClusterSettings = system.settings.config

val clusterClientToFoo = system.actorOf(ClusterClient.props(
        ZookeeperClusterClientSettings(
          system, 
          Some(zookeeperClusterSettings.withValue("name", ConfigValueFactory.fromAnyRef("foo"))))
      ), "clusterClientFoo")
    
val clusterClientToBar = system.actorOf(ClusterClient.props(
        ZookeeperClusterClientSettings(
          system, 
          Some(zookeeperClusterSettings.withValue("name", ConfigValueFactory.fromAnyRef("bar"))))
      ), "clusterClientBar")
``` 