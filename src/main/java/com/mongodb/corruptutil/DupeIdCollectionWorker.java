package com.mongodb.corruptutil;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Projections.include;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.WriteModel;
import com.mongodb.util.CallerBlocksPolicy;

import io.netty.util.concurrent.Future;

public class DupeIdCollectionWorker implements Runnable {
	
	private final static int ONE_MINUTE = 60 * 1000;

    private static Logger logger = LoggerFactory.getLogger(DupeIdCollectionWorker.class);

    private MongoCollection<RawBsonDocument> collection;
    
    private MongoDatabase archiveDb;
    
    private final static int BATCH_SIZE = 1000;
    
    private final static BulkWriteOptions bulkWriteOptions = new BulkWriteOptions().ordered(false);
    
    private Map<String, List<WriteModel<RawBsonDocument>>> writeModelsMap = new HashMap<>();
    
    private List<BsonValue> dupesBatch = new ArrayList<>(BATCH_SIZE);
    
    private String base;
    
    private Integer startId;
    
    Bson sort = eq("_id", 1);
    
    private final int queueSize = 25000;
	private final int threads = 4;
    BlockingQueue<Runnable> workQueue;
    
	protected ThreadPoolExecutor executor = null;
	private ExecutorCompletionService<DupeIdTaskResult> completionService;

    public DupeIdCollectionWorker(MongoCollection<RawBsonDocument> collection, MongoDatabase archiveDb, Integer startId) {
        this.collection = collection;
        this.archiveDb = archiveDb;
        this.startId = startId;
        this.base = collection.getNamespace().getFullName();
        
        workQueue = new ArrayBlockingQueue<>(queueSize);
		executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, workQueue, new CallerBlocksPolicy(ONE_MINUTE*5));
		completionService = new ExecutorCompletionService<>(executor);
    }
    


    @Override
    public void run() {
    	
        MongoCursor<RawBsonDocument> cursor = null;
        long start = System.currentTimeMillis();
        long count = 0;
        long dupeCount = 0;
        long submitCount = 0;

        try {
        	Bson proj = include("_id");
    		Bson query = null;
    		if (startId != null) {
    			query = gte("_id", startId);
    			cursor = collection.find(query).projection(proj).sort(sort).iterator();
    		} else {
    			 cursor = collection.find().projection(proj).sort(sort).iterator();
    		}
    		
            logger.debug("starting worker query {}", collection.getNamespace());
            Number total = collection.estimatedDocumentCount();
            
            logger.debug("{} estimated doc count: {}", collection.getNamespace(), total);
            
            
            
            List<Bson> pipeline = new ArrayList<>();
            pipeline.add(Aggregates.project(Projections.fields(Projections.include("_id"))));
            pipeline.add(Aggregates.sample(25000));
            pipeline.add(Aggregates.sort(Sorts.orderBy(Sorts.ascending("_id"))));
            
            
            AggregateIterable<RawBsonDocument> results = collection.aggregate(pipeline);
            
            BsonValue last = null;
            for (BsonDocument result : results) {
            	BsonValue id = result.get("_id");
            	DupeIdTask task = new DupeIdTask(collection, archiveDb, last, id);
            	completionService.submit(task);
            	submitCount++;
            	last = id;
            }
            logger.debug("submitted {} tasks", submitCount);
                 
            for (int i = 0; i < submitCount; i++) {
            	DupeIdTaskResult result = completionService.take().get();
            	dupeCount += result.dupeCount;
            	count += result.totalCount;
            }
            
        } catch (Exception e) {
            logger.error("worker run error", e);
        } finally {
            
        }
        long end = System.currentTimeMillis();
        Double dur = (end - start) / 1000.0;
        logger.debug(String.format("Done dupe _id check %s, %s documents in %f seconds, dupe id count: %s",
                collection.getNamespace(), count, dur, dupeCount));

    }

}
