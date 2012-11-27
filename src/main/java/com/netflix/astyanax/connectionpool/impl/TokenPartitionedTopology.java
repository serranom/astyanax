package com.netflix.astyanax.connectionpool.impl;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.apache.commons.lang.StringUtils;
import org.cliffc.high_scale_lib.NonBlockingHashMap;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.netflix.astyanax.connectionpool.HostConnectionPool;
import com.netflix.astyanax.connectionpool.LatencyScoreStrategy;
import com.netflix.astyanax.connectionpool.TokenRange;
import com.netflix.astyanax.partitioner.Partitioner;

/**
 * Partition hosts by start token.  Each token may map to a list of partitions.
 * 
 * @author elandau
 *
 * @param <CL>
 */
public class TokenPartitionedTopology<CL> implements Topology<CL> {
    /**
     * Sorted list of partitions.  A binary search is performed on this list to determine
     * the list of hosts that own the token range.
     */
    private AtomicReference<List<TokenHostConnectionPoolPartition<CL>>> sortedRing
    	= new AtomicReference<List<TokenHostConnectionPoolPartition<CL>>>(new ArrayList<TokenHostConnectionPoolPartition<CL>>());

    /**
     * Lookup of end token to partition 
     */
    private NonBlockingHashMap<BigInteger, TokenHostConnectionPoolPartition<CL>> tokenToPartitionMap
    	= new NonBlockingHashMap<BigInteger, TokenHostConnectionPoolPartition<CL>>();

    /**
     * Partition which contains all hosts.  This is the fallback partition when no tokens are provided.
     */
    private TokenHostConnectionPoolPartition<CL> allPools;

    /**
     * Strategy used to score hosts within a partition.
     */
    private LatencyScoreStrategy strategy;

    /**
     * Assume random partitioner, for now
     */
    private final Partitioner partitioner;

    /**
     * Comparator used to find the partition mapping to a token
     */
    @SuppressWarnings("rawtypes")
    private Comparator tokenSearchComparator = new Comparator() {
        @SuppressWarnings("unchecked")
        @Override
        public int compare(Object arg0, Object arg1) {
            TokenHostConnectionPoolPartition<CL> partition = (TokenHostConnectionPoolPartition<CL>) arg0;
            BigInteger token = (BigInteger) arg1;
            return partition.id().compareTo(token);
        }
    };

    /**
     * Compartor used to sort partitions in token order.  
     */
    @SuppressWarnings("rawtypes")
    private Comparator partitionComparator = new Comparator() {
        @SuppressWarnings("unchecked")
        @Override
        public int compare(Object arg0, Object arg1) {
            TokenHostConnectionPoolPartition<CL> partition0 = (TokenHostConnectionPoolPartition<CL>) arg0;
            TokenHostConnectionPoolPartition<CL> partition1 = (TokenHostConnectionPoolPartition<CL>) arg1;
            return partition0.id().compareTo(partition1.id());
        }
    };

    public TokenPartitionedTopology(Partitioner partitioner, LatencyScoreStrategy strategy) {
        this.strategy    = strategy;
        this.partitioner = partitioner;
        this.allPools    = new TokenHostConnectionPoolPartition<CL>(null, this.strategy);
    }

    protected TokenHostConnectionPoolPartition<CL> makePartition(BigInteger partition) {
        return new TokenHostConnectionPoolPartition<CL>(partition, strategy);
    }

    @SuppressWarnings("unchecked")
    @Override
    /**
     * Update the list of pools using the provided mapping of start token to collection of hosts
     * that own the token
     */
    public synchronized boolean setPools(Collection<HostConnectionPool<CL>> ring) {
        boolean didChange = false;
        
        Set<HostConnectionPool<CL>> allPools = Sets.newHashSet();
        
        // Create a mapping of end token to a list of hosts that own the token
        Map<BigInteger, List<HostConnectionPool<CL>>> tokenHostMap = Maps.newHashMap();
        for (HostConnectionPool<CL> pool : ring) {
            allPools.add(pool);
            if (!this.allPools.hasPool(pool))
                didChange = true;
          
            for (TokenRange range : pool.getHost().getTokenRanges()) {
                BigInteger endToken = new BigInteger(range.getEndToken());
                List<HostConnectionPool<CL>> partition = tokenHostMap.get(endToken);
                if (partition == null) {
                    partition = Lists.newArrayList();
                    tokenHostMap.put(endToken, partition);
                }
                partition.add(pool);
            }
        }

        // Temporary list of token that will be removed if not found in the new ring
        Set<BigInteger> tokensToRemove = Sets.newHashSet(tokenHostMap.keySet());

        // Iterate all tokens
        for (Entry<BigInteger, List<HostConnectionPool<CL>>> entry : tokenHostMap.entrySet()) {
            BigInteger token = entry.getKey();
            tokensToRemove.remove(token);
                
            // Add a new partition or modify an existing one
            TokenHostConnectionPoolPartition<CL> partition = tokenToPartitionMap.get(token);
            if (partition == null) {
                partition = makePartition(token);
                tokenToPartitionMap.put(token, partition);
                didChange = true;
            }
            if (partition.setPools(entry.getValue()))
                didChange = true;
        }

        // Remove the tokens that are no longer in the ring
        for (BigInteger token : tokensToRemove) {
            tokenHostMap.remove(token);
            didChange = true;
        }

        // Sort partitions by token
        if (didChange) {
            List<TokenHostConnectionPoolPartition<CL>> partitions = Lists.newArrayList(tokenToPartitionMap.values());
            Collections.sort(partitions, partitionComparator);
            this.allPools.setPools(allPools);
            refresh();
            this.sortedRing.set(Collections.unmodifiableList(partitions));
        }

        return didChange;
    }

    @Override
    public synchronized void resumePool(HostConnectionPool<CL> pool) {
        refresh();
    }

    @Override
    public synchronized void suspendPool(HostConnectionPool<CL> pool) {
        refresh();
    }

    @Override
    public synchronized void refresh() {
        allPools.refresh();
        for (TokenHostConnectionPoolPartition<CL> partition : tokenToPartitionMap.values()) {
            partition.refresh();
        }
        
    }

    @Override
    public TokenHostConnectionPoolPartition<CL> getPartition(ByteBuffer rowkey) {
        if (rowkey == null)
            return getAllPools();
        
        BigInteger token = new BigInteger(partitioner.getTokenForKey(rowkey));
        
        // First, get a copy of the partitions.
    	List<TokenHostConnectionPoolPartition<CL>> partitions = this.sortedRing.get();
        // Must have a token otherwise we default to the base class
        // implementation
        if (token == null || partitions == null || partitions.isEmpty()) {
            return getAllPools();
        }

        // Do a binary search to find the token partition which owns the
        // token. We can get two responses here.
        // 1. The token is in the list in which case the response is the
        // index of the partition
        // 2. The token is not in the list in which case the response is the
        // index where the token would have been inserted into the list.
        // We convert this index (which is negative) to the index of the
        // previous position in the list.
        @SuppressWarnings("unchecked")
        int partitionIndex = Collections.binarySearch(partitions, token, tokenSearchComparator);
        if (partitionIndex < 0) {
            partitionIndex = -(partitionIndex + 1);
        }
        return partitions.get(partitionIndex % partitions.size());
    }

    @Override
    public TokenHostConnectionPoolPartition<CL> getAllPools() {
        return allPools;
    }

    @Override
    public int getPartitionCount() {
        return tokenToPartitionMap.size();
    }

    @Override
    public synchronized void removePool(HostConnectionPool<CL> pool) {
        allPools.removePool(pool);
        for (TokenHostConnectionPoolPartition<CL> partition : tokenToPartitionMap.values()) {
            partition.removePool(pool);
        }
    }

    @Override
    public synchronized void addPool(HostConnectionPool<CL> pool) {
        allPools.addPool(pool);
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TokenPartitionTopology[");
        sb.append(StringUtils.join(Lists.transform(this.sortedRing.get(), new Function<TokenHostConnectionPoolPartition<CL>, String>() {
            @Override
            public String apply(@Nullable TokenHostConnectionPoolPartition<CL> input) {
                return input.id().toString() + "\n";
            }
        }), ","));
        sb.append("]");
        return sb.toString();
    }

}
