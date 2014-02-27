/*
 Copyright 2013 Twitter, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package com.twitter.summingbird.scalding

import com.twitter.algebird.{MapAlgebra, Monoid, Group, Interval, Last}
import com.twitter.algebird.monad._
import com.twitter.summingbird.{Producer, TimeExtractor, TestGraphs}
import com.twitter.summingbird.batch._
import com.twitter.summingbird.batch.state.HDFSState

import java.util.TimeZone
import java.io.File

import com.twitter.scalding.{ Source => ScaldingSource, Test => TestMode, _ }
import com.twitter.scalding.typed.TypedSink

import org.scalacheck._
import org.scalacheck.Prop._
import org.scalacheck.Properties

import org.apache.hadoop.conf.Configuration

import scala.collection.JavaConverters._
import scala.collection.mutable.{ArrayBuffer, Buffer, HashMap => MutableHashMap, Map => MutableMap, SynchronizedBuffer, SynchronizedMap}

import cascading.scheme.local.{TextDelimited => CLTextDelimited}
import cascading.tuple.{Tuple, Fields, TupleEntry}
import cascading.flow.FlowDef
import cascading.tap.Tap
import cascading.scheme.NullScheme
import org.apache.hadoop.mapred.JobConf
import org.apache.hadoop.mapred.RecordReader
import org.apache.hadoop.mapred.OutputCollector


import org.specs2.mutable._

/**
  * Tests for Summingbird's Scalding planner.
  */

object ScaldingLaws extends Specification {
  import MapAlgebra.sparseEquiv

  implicit def timeExtractor[T <: (Long, _)] = TestUtil.simpleTimeExtractor[T]

  def sample[T: Arbitrary]: T = Arbitrary.arbitrary[T].sample.get

  "The ScaldingPlatform" should {
    //Set up the job:
    "match scala for single step jobs" in {
      val original = sample[List[Int]]
      val fn = sample[(Int) => List[(Int, Int)]]
      val initStore = sample[Map[Int, Int]]
      val inMemory = TestGraphs.singleStepInScala(original)(fn)
      // Add a time:
      val inWithTime = original.zipWithIndex.map { case (item, time) => (time.toLong, item) }
      val batcher = TestUtil.randomBatcher(inWithTime)
      val testStore = TestStore[Int,Int]("test", batcher, initStore, inWithTime.size)
      val (buffer, source) = TestSource(inWithTime)

      val summer = TestGraphs.singleStepJob[Scalding,(Long,Int),Int,Int](source, testStore)(t =>
          fn(t._2))

      val scald = Scalding("scalaCheckJob")
      val intr = TestUtil.batchedCover(batcher, 0L, original.size.toLong)
      val ws = new LoopState(intr)
      val mode: Mode = TestMode(t => (testStore.sourceToBuffer ++ buffer).get(t))

      scald.run(ws, mode, scald.plan(summer))
      // Now check that the inMemory ==

      TestUtil.compareMaps(original, Monoid.plus(initStore, inMemory), testStore) must be_==(true)
    }


    "match scala single step pruned jobs" in {
      val original = sample[List[Int]]
      val fn = sample[(Int) => List[(Int, Int)]]
      val initStore = sample[Map[Int, Int]]
      val prunedList = sample[Set[Int]]
      val inMemory = {
        val computedMap = TestGraphs.singleStepInScala(original)(fn)
        val totalMap = Monoid.plus(initStore, computedMap)
        totalMap.filter(kv => !prunedList.contains(kv._1)).toMap
      }

      val pruner = new PrunedSpace[(Int, Int)] {
          def prune(item: (Int, Int), writeTime: Timestamp) = {
            prunedList.contains(item._1)
          }
      }
      // Add a time:
      val inWithTime = original.zipWithIndex.map { case (item, time) => (time.toLong, item) }
      val batcher = TestUtil.randomBatcher(inWithTime)
      val testStore = TestStore[Int,Int]("test", batcher, initStore, inWithTime.size, pruner)
      val (buffer, source) = TestSource(inWithTime)

      val summer = TestGraphs.singleStepJob[Scalding,(Long,Int),Int,Int](source, testStore)(t =>
          fn(t._2))

      val scald = Scalding("scalaCheckJob")
      val intr = TestUtil.batchedCover(batcher, 0L, original.size.toLong)
      val ws = new LoopState(intr)
      val mode: Mode = TestMode(t => (testStore.sourceToBuffer ++ buffer).get(t))

      scald.run(ws, mode, scald.plan(summer))
      // Now check that the inMemory ==

      TestUtil.compareMaps(original, inMemory, testStore) must be_==(true)
    }

    "match scala for flatMapKeys jobs" in {
      val original = sample[List[Int]]
      val initStore = sample[Map[Int,Int]]
      val fnA = sample[(Int) => List[(Int, Int)]]
      val fnB = sample[Int => List[Int]]
      val inMemory = TestGraphs.singleStepMapKeysInScala(original)(fnA, fnB)
      // Add a time:
      val inWithTime = original.zipWithIndex.map { case (item, time) => (time.toLong, item) }
      val batcher = TestUtil.randomBatcher(inWithTime)
      val testStore = TestStore[Int,Int]("test", batcher, initStore, inWithTime.size)

      val (buffer, source) = TestSource(inWithTime)

      val summer = TestGraphs.singleStepMapKeysJob[Scalding,(Long,Int),Int,Int, Int](source, testStore)(t =>
          fnA(t._2), fnB)

      val intr = TestUtil.batchedCover(batcher, 0L, original.size.toLong)
      val scald = Scalding("scalaCheckJob")
      val ws = new LoopState(intr)
      val mode: Mode = TestMode(t => (testStore.sourceToBuffer ++ buffer).get(t))

      scald.run(ws, mode, scald.plan(summer))
      // Now check that the inMemory ==

      TestUtil.compareMaps(original, Monoid.plus(initStore, inMemory), testStore) must beTrue
    }

    "match scala for multiple summer jobs" in {
      val original = sample[List[Int]]
      val initStoreA = sample[Map[Int,Int]]
      val initStoreB = sample[Map[Int,Int]]
      val fnA = sample[(Int) => List[(Int)]]
      val fnB = sample[(Int) => List[(Int, Int)]]
      val fnC = sample[(Int) => List[(Int, Int)]]
      val (inMemoryA, inMemoryB) = TestGraphs.multipleSummerJobInScala(original)(fnA, fnB, fnC)

      // Add a time:
      val inWithTime = original.zipWithIndex.map { case (item, time) => (time.toLong, item) }
      val batcher = TestUtil.randomBatcher(inWithTime)
      val testStoreA = TestStore[Int,Int]("testA", batcher, initStoreA, inWithTime.size)
      val testStoreB = TestStore[Int,Int]("testB", batcher, initStoreB, inWithTime.size)
      val (buffer, source) = TestSource(inWithTime)

      val tail = TestGraphs.multipleSummerJob[Scalding, (Long, Int), Int, Int, Int, Int, Int](source, testStoreA, testStoreB)({t => fnA(t._2)}, fnB, fnC)

      val scald = Scalding("scalaCheckMultipleSumJob")
      val intr = TestUtil.batchedCover(batcher, 0L, original.size.toLong)
      val ws = new LoopState(intr)
      val mode: Mode = TestMode(t => (testStoreA.sourceToBuffer ++ testStoreB.sourceToBuffer ++ buffer).get(t))

      scald.run(ws, mode, scald.plan(tail))
      // Now check that the inMemory ==

      TestUtil.compareMaps(original, Monoid.plus(initStoreA, inMemoryA), testStoreA) must beTrue
      TestUtil.compareMaps(original, Monoid.plus(initStoreB, inMemoryB), testStoreB) must beTrue
    }


    "match scala for leftJoin jobs" in {
      val original = sample[List[Int]]
      val prejoinMap = sample[(Int) => List[(Int, Int)]]
      val service = sample[(Int,Int) => Option[Int]]
      val postJoin = sample[((Int, (Int, Option[Int]))) => List[(Int, Int)]]
      // We need to keep track of time correctly to use the service
      var fakeTime = -1
      val timeIncIt = new Iterator[Int] {
        val inner = original.iterator
        def hasNext = inner.hasNext
        def next = {
          fakeTime += 1
          inner.next
        }
      }
      val srvWithTime = { (key: Int) => service(fakeTime, key) }
      val inMemory = TestGraphs.leftJoinInScala(timeIncIt)(srvWithTime)(prejoinMap)(postJoin)

      // Add a time:
      val allKeys = original.flatMap(prejoinMap).map { _._1 }
      val allTimes = (0 until original.size)
      val stream = for { time <- allTimes; key <- allKeys; v = service(time, key) } yield (time.toLong, (key, v))

      val inWithTime = original.zipWithIndex.map { case (item, time) => (time.toLong, item) }
      val batcher = TestUtil.randomBatcher(inWithTime)
      val initStore = sample[Map[Int, Int]]
      val testStore = TestStore[Int,Int]("test", batcher, initStore, inWithTime.size)

      /**
       * Create the batched service
       */
      val batchedService = stream.map{case (time, v) => (Timestamp(time), v)}.groupBy { case (ts, _) => batcher.batchOf(ts) }
      val testService = new TestService[Int, Int]("srv", batcher, batcher.batchOf(Timestamp(0)).prev, batchedService)

      val (buffer, source) = TestSource(inWithTime)

      val summer =
        TestGraphs.leftJoinJob[Scalding,(Long, Int),Int,Int,Int,Int](source, testService, testStore) { tup => prejoinMap(tup._2) }(postJoin)

      val intr = TestUtil.batchedCover(batcher, 0L, original.size.toLong)
      val scald = Scalding("scalaCheckleftJoinJob")
      val ws = new LoopState(intr)
      val mode: Mode = TestMode(s => (testStore.sourceToBuffer ++ buffer ++ testService.sourceToBuffer).get(s))

      scald.run(ws, mode, summer)
      // Now check that the inMemory ==

      TestUtil.compareMaps(original, Monoid.plus(initStore, inMemory), testStore) must beTrue
    }

    "match scala for diamond jobs with write" in {
      val original = sample[List[Int]]
      val fn1 = sample[(Int) => List[(Int, Int)]]
      val fn2 = sample[(Int) => List[(Int, Int)]]
      val inMemory = TestGraphs.diamondJobInScala(original)(fn1)(fn2)
      // Add a time:
      val inWithTime = original.zipWithIndex.map { case (item, time) => (time.toLong, item) }
      val batcher = TestUtil.randomBatcher(inWithTime)
      val initStore = sample[Map[Int, Int]]
      val testStore = TestStore[Int,Int]("test", batcher, initStore, inWithTime.size)
      val testSink = new TestSink[(Long,Int)]
      val (buffer, source) = TestSource(inWithTime)

      val summer = TestGraphs
        .diamondJob[Scalding,(Long, Int),Int,Int](source,
          testSink,
          testStore)(t => fn1(t._2))(t => fn2(t._2))

      val scald = Scalding("scalding-diamond-Job")
      val intr = TestUtil.batchedCover(batcher, 0L, original.size.toLong)
      val ws = new LoopState(intr)
      val mode: Mode = TestMode(s => (testStore.sourceToBuffer ++ buffer).get(s))

      scald.run(ws, mode, summer)
      // Now check that the inMemory ==

      val sinkOut = testSink.reset
      TestUtil.compareMaps(original, Monoid.plus(initStore, inMemory), testStore) must beTrue
      val wrongSink = sinkOut.map { _._2 }.toList != inWithTime
      wrongSink must be_==(false)
      if(wrongSink) {
        println("input: " + inWithTime)
        println("SinkExtra: " + (sinkOut.map(_._2).toSet -- inWithTime.toSet))
        println("SinkMissing: " + (inWithTime.toSet -- sinkOut.map(_._2).toSet))
      }
    }

    "Correctly aggregate multiple sumByKeys" in {
      val original = sample[List[(Int,Int)]]
      val keyExpand = sample[(Int) => List[Int]]
      val (inMemoryA, inMemoryB) = TestGraphs.twoSumByKeyInScala(original, keyExpand)
      // Add a time:
      val inWithTime = original.zipWithIndex.map { case (item, time) => (time.toLong, item) }
      val batcher = TestUtil.randomBatcher(inWithTime)
      val initStore = sample[Map[Int, Int]]
      val testStoreA = TestStore[Int,Int]("testA", batcher, initStore, inWithTime.size)
      val testStoreB = TestStore[Int,Int]("testB", batcher, initStore, inWithTime.size)
      val (buffer, source) = TestSource(inWithTime)

      val summer = TestGraphs
        .twoSumByKey[Scalding,Int,Int,Int](source.map(_._2), testStoreA, keyExpand, testStoreB)

      val scald = Scalding("scalding-diamond-Job")
      val intr = TestUtil.batchedCover(batcher, 0L, original.size.toLong)
      val ws = new LoopState(intr)
      val mode: Mode = TestMode((testStoreA.sourceToBuffer ++ testStoreB.sourceToBuffer ++ buffer).get(_))

      scald.run(ws, mode, summer)
      // Now check that the inMemory ==

      TestUtil.compareMaps(original, Monoid.plus(initStore, inMemoryA), testStoreA, "A") must beTrue
      TestUtil.compareMaps(original, Monoid.plus(initStore, inMemoryB), testStoreB, "B") must beTrue
    }
  }
}
