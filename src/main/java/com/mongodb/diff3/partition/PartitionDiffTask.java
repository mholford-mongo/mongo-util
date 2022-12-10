package com.mongodb.diff3.partition;

import com.mongodb.client.MongoClient;
import com.mongodb.diff3.DiffConfiguration;
import com.mongodb.diff3.DiffResult;
import com.mongodb.diff3.DiffSummary2;
import com.mongodb.diff3.DiffTask;
import com.mongodb.diff3.RetryStatus;
import com.mongodb.diff3.RetryTask;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;

import java.util.Queue;


public class PartitionDiffTask extends DiffTask {


    private final Partition partition;
    private final MongoClient sourceClient;
    private final MongoClient destClient;
    public static final PartitionDiffTask END_TOKEN = new PartitionDiffTask(
            null, null, null, null, null, null);


    public PartitionDiffTask(Partition partition, MongoClient sourceClient, MongoClient destClient,
                             Queue<RetryTask> retryQueue, DiffSummary2 summary, DiffConfiguration config) {
        super(config, (partition == null) ? null : partition.getNamespace(), retryQueue, summary);
        this.partition = partition;
        this.sourceClient = sourceClient;
        this.destClient = destClient;
        if (this.partition != null) {
            this.chunkDef = this.partition.toChunkDef();
        }
    }

    //    @Override
    public Bson getPartitionDiffQuery() {
        return (partition != null) ? partition.query() : new BsonDocument();
    }

//    @Override
//    protected Pair<Bson, Bson> getChunkBounds() {
//        return null;
//    }

    @Override
    protected String unitString() {
        return partition.toString();
    }

    @Override
    protected DiffResult initDiffResult() {
        PartitionDiffResult diffResult = new PartitionDiffResult();
        diffResult.setNamespace(namespace);
        diffResult.setPartition(partition);
        return diffResult;
    }

    @Override
    protected PartitionRetryTask endToken() {
        return PartitionRetryTask.END_TOKEN;
    }

    @Override
    protected MongoClient getLoadClient(Target target) {
        switch (target) {
            case SOURCE:
                return sourceClient;
            case DEST:
                return destClient;
            default:
                throw new RuntimeException("Unknown target: " + target);
        }
    }

    @Override
    protected PartitionRetryTask createRetryTask(RetryStatus retryStatus, DiffResult result) {
        return new PartitionRetryTask(retryStatus, this, (PartitionDiffResult) result,
                result.getFailedKeys(), retryQueue, summary);
    }

    public Partition getPartition() {
        return partition;
    }
}
