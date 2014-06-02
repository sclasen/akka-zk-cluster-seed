import com.typesafe.sbt.SbtScalariform._

import com.typesafe.sbt.SbtMultiJvm

import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

organization := "com.sclasen"

name := "akka-zk-cluster-seed"

version := "0.0.1-SNAPSHOT"

scalaVersion := "2.10.4"

parallelExecution in Test := false

scalariformSettings

libraryDependencies ++= (akkaDependencies ++ zkDependencies ++ testDependencies)

parallelExecution in Test := false


pomExtra := (
  <url>http://github.com/sclasen/akka-zk-cluster-seed</url>
    <licenses>
      <license>
        <name>The Apache Software License, Version 2.0</name>
        <url>http://www.apache.org/licenses/LICENSE-2.0.html</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:sclasen/akka-zk-cluster-seed.git</url>
      <connection>scm:git:git@github.com:sclasen/akka-zk-cluster-seed.git</connection>
    </scm>
    <developers>
      <developer>
        <id>sclasen</id>
        <name>Scott Clasen</name>
        <url>http://github.com/sclasen</url>
      </developer>
    </developers>)


publishTo <<= version {
  (v: String) =>
    val nexus = "https://oss.sonatype.org/"
    if (v.trim.endsWith("SNAPSHOT")) Some("snapshots" at nexus + "content/repositories/snapshots")
    else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

val root = rootProject

def rootProject = Project("akka-zk-cluster-seed", file("."))
  .settings(Project.defaultSettings:_*)
  .settings(
    scalacOptions in Compile ++= Seq("-encoding", "UTF-8", "-target:jvm-1.6", "-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"),
    javacOptions in Compile ++= Seq("-source", "1.6", "-target", "1.6", "-Xlint:unchecked", "-Xlint:deprecation")
  )
  .settings(Defaults.itSettings:_*)
  .settings(SbtMultiJvm.multiJvmSettings:_*)
  .settings(compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in IntegrationTest))
  .settings(executeTests in IntegrationTest <<= (executeTests in Test, executeTests in MultiJvm) map {
  case (testResults, multiNodeResults)  =>
    val overall =
      if (testResults.overall.id < multiNodeResults.overall.id)
        multiNodeResults.overall
      else
        testResults.overall
    Tests.Output(overall,
      testResults.events ++ multiNodeResults.events,
      testResults.summaries ++ multiNodeResults.summaries)
 })
  .configs(IntegrationTest, MultiJvm)

def akkaDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.3.2" % "provided",
  "com.typesafe.akka" %% "akka-cluster" % "2.3.2" % "provided",
  "com.typesafe.akka" %% "akka-slf4j" % "2.3.2" % "provided",
  "org.slf4j" % "log4j-over-slf4j" % "1.6.6" % "provided",
  "ch.qos.logback" % "logback-classic" % "1.1.2"  % "provided"
)

def zkDependencies = Seq(
  "org.apache.curator" % "curator-framework" % "2.5.0",
  "org.apache.curator" % "curator-recipes" % "2.5.0"
)

def testDependencies = Seq(
  "com.typesafe.akka" %% "akka-testkit" % "2.3.2" % "test,it,multi-jvm",
  "com.typesafe.akka" %% "akka-actor" % "2.3.2" % "test,it,multi-jvm",
  "com.typesafe.akka" %% "akka-cluster" % "2.3.2" % "test,it,multi-jvm",
  "org.scalatest" %% "scalatest" % "2.1.6" % "test,it,multi-jvm",
  "com.typesafe.akka" %% "akka-multi-node-testkit" % "2.3.2" % "test,it,multi-jvm"
)

// needs to come after all dependencies

net.virtualvoid.sbt.graph.Plugin.graphSettings
