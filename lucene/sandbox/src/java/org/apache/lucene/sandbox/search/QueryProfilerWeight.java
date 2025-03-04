/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.sandbox.search;

import java.io.IOException;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.BulkScorer;
import org.apache.lucene.search.FilterWeight;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.Weight;

/**
 * Weight wrapper that will compute how much time it takes to build the {@link Scorer} and then
 * return a {@link Scorer} that is wrapped in order to compute timings as well.
 */
class QueryProfilerWeight extends FilterWeight {

  private final QueryProfilerBreakdown profile;

  public QueryProfilerWeight(Weight subQueryWeight, QueryProfilerBreakdown profile) {
    super(subQueryWeight);
    this.profile = profile;
  }

  @Override
  public int count(LeafReaderContext context) throws IOException {
    QueryProfilerTimer timer = profile.getTimer(QueryProfilerTimingType.COUNT);
    timer.start();
    try {
      return in.count(context);
    } finally {
      timer.stop();
    }
  }

  @Override
  public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
    QueryProfilerTimer timer = profile.getTimer(QueryProfilerTimingType.BUILD_SCORER);
    timer.start();
    final ScorerSupplier subQueryScorerSupplier;
    try {
      subQueryScorerSupplier = in.scorerSupplier(context);
    } finally {
      timer.stop();
    }
    if (subQueryScorerSupplier == null) {
      return null;
    }
    return new ScorerSupplier() {

      @Override
      public Scorer get(long loadCost) throws IOException {
        timer.start();
        try {
          return new QueryProfilerScorer(subQueryScorerSupplier.get(loadCost), profile);
        } finally {
          timer.stop();
        }
      }

      @Override
      public BulkScorer bulkScorer() throws IOException {
        // We use the default bulk scorer instead of the specialized one. The reason
        // is that BulkScorers do everything at once: finding matches,
        // scoring them and calling the collector, so they make it impossible to
        // see where time is spent, which is the purpose of query profiling.
        // The default bulk scorer will pull a scorer and iterate over matches,
        // this might be a significantly different execution path for some queries
        // like disjunctions, but in general this is what is done anyway
        return super.bulkScorer();
      }

      @Override
      public long cost() {
        timer.start();
        try {
          return subQueryScorerSupplier.cost();
        } finally {
          timer.stop();
        }
      }

      @Override
      public void setTopLevelScoringClause() {
        subQueryScorerSupplier.setTopLevelScoringClause();
      }
    };
  }

  @Override
  public boolean isCacheable(LeafReaderContext ctx) {
    return false;
  }
}
