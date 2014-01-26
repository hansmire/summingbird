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

import com.twitter.summingbird.batch

package object option {
  @deprecated("Use com.twitter.summingbird.batch.option.FlatMapShards", "0.3.2")
  type FlatMapShards = batch.option.FlatMapShards

  @deprecated("Use com.twitter.summingbird.batch.option.FlatMapShards", "0.3.2")
  val FlatMapShards = batch.option.FlatMapShards

  @deprecated("Use com.twitter.summingbird.batch.option.Reducers", "0.3.2")
  type Reducers = batch.option.Reducers

  @deprecated("Use com.twitter.summingbird.batch.option.Reducers", "0.3.2")
  val Reducers = batch.option.Reducers
}
