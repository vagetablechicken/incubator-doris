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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Maps;
import org.apache.doris.common.Pair;
import org.apache.doris.thrift.TStorageMedium;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/*
 * MovesInProgressCache caches the in-progress moves for each cluster and each medium(every cache could be called Cell).
 * The expireAfterAccess config can be reset when updating, it will clear the current cache.
 */
public class MovesInProgressCache {
    private static final Logger LOG = LogManager.getLogger(MovesInProgressCache.class);

    // cluster -> medium -> moves in progress
    private final Map<String, Map<TStorageMedium, Cell>> movesInProgressMap = Maps.newHashMap();
    private long lastExpireConfig = -1L;

    // TabletId -> Pair<Move, ToDeleteReplicaId>, 'ToDeleteReplicaId == -1' means this move haven't been scheduled successfully.
    public static class Cell {
        Cache<Long, Pair<PartitionRebalancer.ReplicaMove, Long>> cache;

        Cell(long duration, TimeUnit unit) {
            cache = CacheBuilder.newBuilder().expireAfterAccess(duration, unit).build();
        }

        public Cache<Long, Pair<PartitionRebalancer.ReplicaMove, Long>> get() {
            return cache;
        }
    }

    public void updateMapping(Map<String, ClusterLoadStatistic> statisticMap, long expireAfterAccessSecond) {
        if (lastExpireConfig != expireAfterAccessSecond) {
            LOG.debug("Reset cache expireAfterAccess, last {}s, now {}s. Cache will be cleared.", lastExpireConfig, expireAfterAccessSecond);
            movesInProgressMap.clear();
            lastExpireConfig = expireAfterAccessSecond;
        }

        movesInProgressMap.keySet().stream().filter(k -> !statisticMap.containsKey(k)).forEach(movesInProgressMap::remove);

        List<String> toAdd = statisticMap.keySet().stream().filter(k -> !movesInProgressMap.containsKey(k)).collect(Collectors.toList());
        for (String cluster : toAdd) {
            Map<TStorageMedium, Cell> cacheMap = Maps.newHashMap();
            Arrays.stream(TStorageMedium.values()).forEach(m -> cacheMap.put(m, new Cell(expireAfterAccessSecond, TimeUnit.SECONDS)));
            movesInProgressMap.put(cluster, cacheMap);
        }
    }

    public Cell getCache(String clusterName, TStorageMedium medium) {
        Map<TStorageMedium, Cell> clusterMoves = movesInProgressMap.get(clusterName);
        if (clusterMoves != null) {
            return clusterMoves.get(medium);
        }
        return null;
    }

    // For each cache Cell, performs any pending maintenance operations needed by the cache.
    public void cleanUp() {
        movesInProgressMap.values().forEach(maps -> maps.values().forEach(map -> map.get().cleanUp()));
    }

    public long size() {
        return movesInProgressMap.values().stream().mapToLong(maps -> maps.values().stream().mapToLong(map -> map.get().size()).sum()).sum();
    }

    @Override
    public String toString() {
        StringJoiner sj = new StringJoiner("\n", "MovesInProgress detail:\n", "");
        movesInProgressMap.forEach((key, value) -> value.forEach((k, v) -> sj.add("(" + key + "-" + k + ": " + v.get().asMap() + ")")));
        return sj.toString();
    }
}
