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

import com.twitter.algebird.Semigroup
import com.twitter.algebird.monad.{StateWithError, Reader}
import com.twitter.scalding.{Dsl, TypedPipe, MapsideReduce, TupleSetter, TupleConverter}
import com.twitter.summingbird._
import com.twitter.summingbird.option._
import cascading.flow.FlowDef


import org.slf4j.LoggerFactory

object Store extends java.io.Serializable {
  // This could be moved to scalding, but the API needs more design work
  // This DOES NOT trigger a grouping
  def mapsideReduce[K,V](pipe: TypedPipe[(K, V)])(implicit sg: Semigroup[V]): TypedPipe[(K, V)] = {
    import Dsl._
    val fields = ('key, 'value)
    val gpipe = pipe.toPipe(fields)(TupleSetter.tup2Setter[(K,V)])
    val msr = new MapsideReduce(sg, fields._1, fields._2, None)(
      TupleConverter.singleConverter[V], TupleSetter.singleSetter[V])
    TypedPipe.from(gpipe.eachTo(fields -> fields) { _ => msr }, fields)(TupleConverter.of[(K, V)])
  }
}

trait Store[K, V] extends java.io.Serializable {
  /**
    * Accepts deltas along with their timestamps, returns triples of
    * (time, K, V(aggregated up to the time)).
    *
    * Same return as lookup on a ScaldingService.
    */
  def merge(delta: PipeFactory[(K, V)],
    sg: Semigroup[V],
    commutativity: Commutativity,
    reducers: Int): PipeFactory[(K, (Option[V], V))]

  /** This is an optional method, by default it a pass-through.
   * it may be called by ScaldingPlatform before a key transformation
   * that leads only to this store.
   */
  def partialMerge[K1](delta: PipeFactory[(K1, V)],
    sg: Semigroup[V],
    commutativity: Commutativity): PipeFactory[(K1, V)] = delta
}
