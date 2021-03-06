/*
 * Copyright (c) 2013-2016 Erik van Oosten
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nl.grons.metrics.scala

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit._
import com.codahale.metrics._
import com.typesafe.config.ConfigFactory
import nl.grons.metrics.scala.ActorInstrumentedLifeCycleSpec._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, Matchers}

import scala.concurrent.Promise
import scala.collection.JavaConverters._

object ActorInstrumentedLifeCycleSpec {

  // Don't log all those intentional exceptions
  val NonLoggingActorSystem = ActorSystem("lifecycle-spec", ConfigFactory.parseMap(Map("akka.loglevel" -> "OFF").asJava))

  val TestMetricRegistry = new com.codahale.metrics.MetricRegistry()

  trait Instrumented extends InstrumentedBuilder {
    val metricRegistry = TestMetricRegistry
  }

  class ExampleActor(restarted: Promise[Boolean]) extends Actor with Instrumented with ActorInstrumentedLifeCycle {

    var counter = 0

    // The following gauge will automatically unregister before a restart of this actor.
    metrics.gauge("sample-gauge") {
      counter
    }

    override def preRestart(reason: Throwable, message: Option[Any]) = {
      self ! 'prerestart
      super.preRestart(reason, message)
    }

    def receive = {
      case 'increment =>
        counter += 1
      case 'get =>
        sender ! counter
      case 'error =>
        throw new RuntimeException("BOOM!")
      case 'prerestart =>
        restarted.success(true)
    }

  }
}

class ActorInstrumentedLifeCycleSpec extends TestKit(NonLoggingActorSystem) with FunSpecLike with ImplicitSender with Matchers with ScalaFutures with BeforeAndAfterAll {

  def report = {
    case class NameFilter(prefix: String) extends MetricFilter {
      override def matches(name: String, metric: Metric): Boolean = name.startsWith(prefix)
    }

    TestMetricRegistry
      .getGauges(NameFilter("nl.grons.metrics.scala")).asScala
      .headOption
      .map {case (_,g) => g.getValue}
  }

  describe("an actor with gauges needing lifecycle management") {
    val restartedPromise = Promise[Boolean]()
    val ar = system.actorOf(Props(new ExampleActor(restartedPromise)))

    it("correctly builds a gauge that reports the correct value") {
      ar ! 'increment
      ar ! 'increment
      ar ! 'increment
      ar ! 'get
      expectMsg(3)
      report shouldBe Some(3)
    }

    it("rebuilds the gauge on actor restart") {
      ar ! 'increment
      ar ! 'increment
      ar ! 'error
      ar ! 'increment
      ar ! 'get
      expectMsg(1)
      whenReady(restartedPromise.future)(_ shouldBe true)
      report shouldBe Some(1)
    }
  }

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }
}
