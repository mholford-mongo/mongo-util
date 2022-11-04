package com.mongodb.diff3;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import com.mongodb.model.Collection;
import org.bson.RawBsonDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.model.DatabaseCatalog;
import com.mongodb.shardsync.ShardClient;
import com.mongodb.util.BlockWhenQueueFull;

public class DiffUtil {

    private static Logger logger = LoggerFactory.getLogger(DiffUtil.class);


    private ShardClient sourceShardClient;
    private ShardClient destShardClient;

    private DiffConfiguration config;

    protected ThreadPoolExecutor executor = null;
    private BlockingQueue<Runnable> workQueue;
    List<Future<DiffResult>> diffResults;

    private Map<String, RawBsonDocument> sourceChunksCache;
    private long estimatedTotalDocs;
    private long totalSize;


    public DiffUtil(DiffConfiguration config) {
        this.config = config;

        sourceShardClient = new ShardClient("source", config.getSourceClusterUri());
        destShardClient = new ShardClient("dest", config.getDestClusterUri());

        sourceShardClient.init();
        destShardClient.init();

        Set<String> includeNs = config.getIncludeNamespaces().stream()
                .map(n -> n.getNamespace()).collect(Collectors.toSet());
        sourceShardClient.populateCollectionsMap(includeNs);
        DatabaseCatalog catalog = sourceShardClient.getDatabaseCatalog(config.getIncludeNamespaces());

        long[] sizeAndCount = catalog.getTotalSizeAndCount();
        totalSize = sizeAndCount[0];
        estimatedTotalDocs = sizeAndCount[1];

        Set<String> shardedColls = catalog.getShardedCollections().stream()
                .map(c -> c.getNamespace()).collect(Collectors.toSet());
        Set<String> unshardedColls = catalog.getUnshardedCollections().stream()
                .map(c -> c.getNamespace()).collect(Collectors.toSet());

        logger.info("ShardedColls:[" + String.join(", ", shardedColls) + "]");

        logger.info("UnshardedColls:[" + String.join(", ", unshardedColls) + "]");
//        sourceShardClient.populateCollectionsMap();
        sourceChunksCache = sourceShardClient.loadChunksCache(config.getChunkQuery());

        int qSize = sourceChunksCache.size() + unshardedColls.size();
//        int qSize = 1;
        logger.debug("Setting workQueue size to {}", qSize);
        workQueue = new ArrayBlockingQueue<Runnable>(qSize);
        diffResults = new ArrayList<>(sourceChunksCache.size());
        executor = new ThreadPoolExecutor(config.getThreads(), config.getThreads(), 30, TimeUnit.SECONDS, workQueue, new BlockWhenQueueFull());
    }


    public void run() {
        DiffSummary summary = new DiffSummary(sourceChunksCache.size(), estimatedTotalDocs, totalSize);

        ScheduledExecutorService statusReporter = Executors.newSingleThreadScheduledExecutor();
        statusReporter.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                logger.info(summary.getSummary(false));
            }
        }, 0, 5, TimeUnit.SECONDS);

        for (RawBsonDocument chunk : sourceChunksCache.values()) {
            ShardedDiffTask task = new ShardedDiffTask(sourceShardClient, destShardClient, config, chunk);
//            logger.debug("Added a ShardedDiffTask");
            diffResults.add(executor.submit(task));
        }

        for (Collection unshardedColl : sourceShardClient.getDatabaseCatalog().getUnshardedCollections()) {
            UnshardedDiffTask task = new UnshardedDiffTask(sourceShardClient, destShardClient, unshardedColl.getNamespace());
            logger.debug("Added an UnshardedDiffTask for {}", unshardedColl.getNamespace());
            diffResults.add(executor.submit(task));
        }

        Set<Future<DiffResult>> futuresSeen = new HashSet<>();

        while (futuresSeen.size() < diffResults.size()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
//            logger.debug("LOOP results");
            for (Future<DiffResult> future : diffResults) {
                try {
                    if (!futuresSeen.contains(future) && future.isDone()) {
                        futuresSeen.add(future);
                        DiffResult result = future.get();
                        if (result instanceof UnshardedDiffResult) {
                            UnshardedDiffResult udr = (UnshardedDiffResult) result;
                            logger.debug("Got unsharded result for {}: {} matches, {} failures, {} bytes", udr.getNs(),
                                    udr.matches, udr.getFailureCount(), udr.bytesProcessed);
                        } else if (result instanceof ShardedDiffResult) {
                            ShardedDiffResult sdr = (ShardedDiffResult) result;
                            logger.debug("Got sharded result for {} -- {}: {} matches, {} failures, {} bytes", sdr.getNs(),
                                    sdr.getChunkQuery(), sdr.matches, sdr.getFailureCount(), sdr.bytesProcessed);
                        } else {
                            throw new RuntimeException("What the fuck?");
                        }
                        int failures = result.getFailureCount();

                        if (failures > 0) {
                            if (result instanceof ShardedDiffResult) {
                                summary.incrementFailedChunks(1);
                            }
                            summary.incrementSuccessfulDocs(result.matches - failures);
                        } else {
                            if (result instanceof ShardedDiffResult) {
                                summary.incrementSuccessfulChunks(1);
                            }
                            summary.incrementSuccessfulDocs(result.matches);
                        }


                        summary.incrementProcessedDocs(result.matches + failures);
                        summary.incrementFailedDocs(failures);
                        if (result instanceof ShardedDiffResult) {
                            summary.incrementProcessedChunks(1);
                        }
                        summary.incrementSourceOnly(result.onlyOnSource);
                        summary.incrementDestOnly(result.onlyOnDest);
                        summary.incrementProcessedSize(result.bytesProcessed);
                    }

//                logger.debug("result: {}", result);
                } catch (InterruptedException e) {
                    logger.error("Diff task was interrupted", e);
                } catch (ExecutionException e) {
                    logger.error("Diff task threw an exception", e);
                }
            }
        }
        statusReporter.shutdown();

        executor.shutdown();
        logger.info(summary.getSummary(true));
    }


}
