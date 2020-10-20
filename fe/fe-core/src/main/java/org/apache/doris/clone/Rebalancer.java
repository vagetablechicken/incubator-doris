// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.clone;

import org.apache.doris.catalog.Catalog;
import org.apache.doris.catalog.TabletInvertedIndex;
import org.apache.doris.clone.TabletScheduler.PathSlot;
import org.apache.doris.system.SystemInfoService;
import org.apache.doris.task.AgentBatchTask;
import org.apache.doris.thrift.TStorageMedium;

import com.google.common.collect.Lists;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;


/*
 * Rebalancer is responsible for
 * 1. selectAlternativeTablets: selecting alternative tablets by one rebalance strategy,
 * and return them to tablet scheduler(maybe contains the concrete moves, or maybe not).
 * 2. createBalanceTask: given a tablet, try to create a clone task for this tablet.
 * 3. getCachedSrcBackendId: if the rebalance strategy wants to delete the replica of a specified be,
 * override this func.
 * NOTICE:
 * It may have a long interval between selectAlternativeTablets() & createBalanceTask(). So the concrete moves may be
 * invalid when we createBalanceTask(), you should check the moves' validation.
 */
public abstract class Rebalancer {
    private static final Logger LOG = LogManager.getLogger(Rebalancer.class);

    // When Rebalancer init, the statisticMap is empty so that it's no need to be an arg.
    // Use updateLoadStatistic() to load stats only.
    protected Map<String, ClusterLoadStatistic> statisticMap;
    protected TabletInvertedIndex invertedIndex;
    protected SystemInfoService infoService;

    public Rebalancer(SystemInfoService infoService, TabletInvertedIndex invertedIndex) {
        this.infoService = infoService;
        this.invertedIndex = invertedIndex;
    }

    public List<TabletSchedCtx> selectAlternativeTablets() {
        List<TabletSchedCtx> alternativeTablets = Lists.newArrayList();
        for (Map.Entry<String, ClusterLoadStatistic> entry : statisticMap.entrySet()) {
            for (TStorageMedium medium : TStorageMedium.values()) {
                alternativeTablets.addAll(selectAlternativeTabletsForCluster(entry.getKey(),
                        entry.getValue(), medium));
            }
        }
        return alternativeTablets;
    }

    protected abstract List<TabletSchedCtx> selectAlternativeTabletsForCluster(
            String clusterName, ClusterLoadStatistic clusterStat, TStorageMedium medium);

    public abstract void createBalanceTask(TabletSchedCtx tabletCtx, Map<Long, PathSlot> backendsWorkingSlots,
                                           AgentBatchTask batchTask) throws SchedException;

    public Long getCachedSrcBackendId(Long tabletId) {
        return -1L;
    }

    public void updateLoadStatistic(Map<String, ClusterLoadStatistic> statisticMap) {
        this.statisticMap = statisticMap;
    }
}
