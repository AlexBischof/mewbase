package com.tesco.mewbase.server.impl;

import com.tesco.mewbase.bson.BsonObject;
import com.tesco.mewbase.common.SubDescriptor;
import com.tesco.mewbase.doc.DocManager;
import com.tesco.mewbase.doc.DocReadStream;
import com.tesco.mewbase.log.Log;
import com.tesco.mewbase.query.QueryInfo;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.parsetools.RecordParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by tim on 23/09/16.
 */
public class ConnectionImpl implements ServerFrameHandler {

    private final static Logger logger = LoggerFactory.getLogger(ConnectionImpl.class);

    private final ServerImpl server;
    private final TransportConnection transportConnection;
    private final Context context;
    private final Map<Integer, SubscriptionImpl> subscriptionMap = new ConcurrentHashMap<>();
    private final PriorityQueue<WriteHolder> pq = new PriorityQueue<>();
    private final Map<Integer, QueryExecution> queryExecutions = new ConcurrentHashMap<>();
    private boolean authorised;
    private int subSeq;
    private long writeSeq;
    private long expectedRespNo;

    public ConnectionImpl(ServerImpl server, TransportConnection transportConnection, Context context, DocManager docManager) {
        Protocol protocol = new Protocol(this);
        RecordParser recordParser = protocol.recordParser();
        transportConnection.handler(recordParser::handle);
        this.server = server;
        this.transportConnection = transportConnection;
        this.context = context;
    }

    @Override
    public void handleConnect(BsonObject frame) {
        checkContext();
        // TODO auth
        // TODO version checking
        authorised = true;
        BsonObject resp = new BsonObject();
        resp.put(Protocol.RESPONSE_OK, true);
        writeResponse(Protocol.RESPONSE_FRAME, resp, getWriteSeq());
    }

    @Override
    public void handlePublish(BsonObject frame) {
        checkContext();
        checkAuthorised();
        String channel = frame.getString(Protocol.PUBLISH_CHANNEL);
        BsonObject event = frame.getBsonObject(Protocol.PUBLISH_EVENT);
        if (channel == null) {
            logAndClose("No channel in PUB");
            return;
        }
        if (event == null) {
            logAndClose("No event in PUB");
            return;
        }
        long order = getWriteSeq();
        Log log = server.getLog(channel);
        BsonObject record = new BsonObject();
        record.put(Protocol.RECEV_TIMESTAMP, System.currentTimeMillis());
        record.put(Protocol.RECEV_EVENT, event);
        CompletableFuture<Long> cf = log.append(record);

        cf.handle((v, ex) -> {
            BsonObject resp = new BsonObject();
            if (ex == null) {
                resp.put(Protocol.RESPONSE_OK, true);
            } else {
                // TODO error code
                resp.put(Protocol.RESPONSE_OK, false).put(Protocol.RESPONSE_ERRMSG, "Failed to persist");
            }
            writeResponse(Protocol.RESPONSE_FRAME, resp, order);
            return null;
        });
    }

    @Override
    public void handleStartTx(BsonObject frame) {
        checkContext();
        checkAuthorised();
    }

    @Override
    public void handleCommitTx(BsonObject frame) {
        checkContext();
        checkAuthorised();
    }

    @Override
    public void handleAbortTx(BsonObject frame) {
        checkContext();
        checkAuthorised();
    }

    @Override
    public void handleSubscribe(BsonObject frame) {
        checkContext();
        checkAuthorised();
        String channel = frame.getString(Protocol.SUBSCRIBE_CHANNEL);
        if (channel == null) {
            logAndClose("No channel in SUBSCRIBE");
            return;
        }
        Long startSeq = frame.getLong(Protocol.SUBSCRIBE_STARTPOS);
        Long startTimestamp = frame.getLong(Protocol.SUBSCRIBE_STARTTIMESTAMP);
        String durableID = frame.getString(Protocol.SUBSCRIBE_DURABLEID);
        BsonObject matcher = frame.getBsonObject(Protocol.SUBSCRIBE_MATCHER);
        SubDescriptor subDescriptor = new SubDescriptor().setStartPos(startSeq == null ? -1 : startSeq).setStartTimestamp(startTimestamp)
                .setMatcher(matcher).setDurableID(durableID).setChannel(channel);
        int subID = subSeq++;
        checkWrap(subSeq);
        Log log = server.getLog(channel);
        if (log == null) {
            // TODO send error back to client
            throw new IllegalStateException("No such channel " + channel);
        }
        SubscriptionImpl subscription = new SubscriptionImpl(this, subID, subDescriptor);
        subscriptionMap.put(subID, subscription);
        BsonObject resp = new BsonObject();
        resp.put(Protocol.RESPONSE_OK, true);
        resp.put(Protocol.SUBRESPONSE_SUBID, subID);
        writeResponse(Protocol.SUBRESPONSE_FRAME, resp, getWriteSeq());
        logger.trace("Subscribed channel: {} startSeq {}", channel, startSeq);
    }

    @Override
    public void handleSubClose(BsonObject frame) {
        handleCloseUnsubscribeSub(frame, false);
    }

    @Override
    public void handleUnsubscribe(BsonObject frame) {
        handleCloseUnsubscribeSub(frame, true);
    }

    @Override
    public void handleAckEv(BsonObject frame) {
        checkContext();
        checkAuthorised();
        Integer subID = frame.getInteger(Protocol.ACKEV_SUBID);
        if (subID == null) {
            logAndClose("No subID in ACKEV");
            return;
        }
        Integer bytes = frame.getInteger(Protocol.ACKEV_BYTES);
        if (bytes == null) {
            logAndClose("No bytes in ACKEV");
            return;
        }
        Long pos = frame.getLong(Protocol.ACKEV_POS);
        if (pos == null) {
            logAndClose("No pos in ACKEV");
            return;
        }
        SubscriptionImpl subscription = subscriptionMap.get(subID);
        if (subscription == null) {
            logAndClose("Invalid subID in ACKEV");
            return;
        }
        subscription.handleAckEv(pos, bytes);
    }

    @Override
    public void handleQuery(BsonObject frame) {
        checkContext();
        checkAuthorised();
        int queryID = frame.getInteger(Protocol.QUERY_QUERYID);
        String binder = frame.getString(Protocol.QUERY_BINDER);
        String docID = frame.getString(Protocol.QUERY_DOCID);
        DocManager docManager = server().docManager();
        if (docID != null) {
            // Query by ID
            CompletableFuture<BsonObject> cf = docManager.get(binder, docID);
            cf.thenAccept(doc -> writeQueryResult(doc, queryID, true));
        } else {
            String queryName = frame.getString(Protocol.QUERY_NAME);
            if (queryName != null) {
                // Server defined query
                BsonObject params = frame.getBsonObject(Protocol.QUERY_PARAMS);
                QueryInfo queryInfo = server.queryManager().getQuery(queryName);
                if (queryInfo == null) {
                    // TODO handle this
                } else {
                    DocReadStream readStream = docManager.openStream(queryInfo.binderName);
                    QueryExecution state = new QueryExecution(this, queryID, readStream, params,
                                                              queryInfo.queryConsumer);
                    queryExecutions.put(queryID, state);
                    readStream.start();
                }
            } else {
                // TODO handle this
            }
        }
    }

    @Override
    public void handleQueryAck(BsonObject frame) {
        checkContext();
        checkAuthorised();
        Integer queryID = frame.getInteger(Protocol.QUERYACK_QUERYID);
        if (queryID == null) {
            logAndClose("No queryID in QueryAck");
        } else {
            Integer bytes = frame.getInteger(Protocol.QUERYACK_BYTES);
            if (bytes == null) {
                logAndClose("No bytes in QueryAck");
                return;
            }
            QueryExecution queryExecution = queryExecutions.get(queryID);
            if (queryExecution != null) {
                queryExecution.handleAck(bytes);
            }
        }
    }

    @Override
    public void handlePing(BsonObject frame) {
        checkContext();
        checkAuthorised();
    }

    protected Buffer writeQueryResult(BsonObject doc, int queryID, boolean last) {
        BsonObject res = new BsonObject();
        res.put(Protocol.QUERYRESULT_QUERYID, queryID);
        res.put(Protocol.QUERYRESULT_RESULT, doc);
        res.put(Protocol.QUERYRESULT_LAST, last);
        return writeNonResponse(Protocol.QUERYRESULT_FRAME, res);
    }

    protected Buffer writeNonResponse(String frameName, BsonObject frame) {
        Buffer buff = Protocol.encodeFrame(frameName, frame);
        // TODO compare performance of writing directly in all cases and via context
        Context curr = Vertx.currentContext();
        if (curr != context) {
            context.runOnContext(v -> transportConnection.write(buff));
        } else {
            transportConnection.write(buff);
        }
        return buff;
    }

    protected void writeResponse(String frameName, BsonObject frame, long order) {
        Buffer buff = Protocol.encodeFrame(frameName, frame);
        // TODO compare performance of writing directly in all cases and via context
        Context curr = Vertx.currentContext();
        if (curr != context) {
            context.runOnContext(v -> writeResponseOrdered(buff, order));
        } else {
            writeResponseOrdered(buff, order);
        }
    }

    protected void writeResponseOrdered(Buffer buff, long order) {
        checkContext();
        // Writes can come in in the wrong order, we need to make sure they are written in the correct order
        if (order == expectedRespNo) {
            writeResponseOrdered0(buff);
        } else {
            // Out of order
            pq.add(new WriteHolder(order, buff));
        }
        while (true) {
            WriteHolder head = pq.peek();
            if (head != null && head.order == expectedRespNo) {
                pq.poll();
                writeResponseOrdered0(head.buff);
            } else {
                break;
            }
        }
    }

    protected long getWriteSeq() {
        long seq = writeSeq;
        writeSeq++;
        checkWrap(writeSeq);
        return seq;
    }

    protected void checkWrap(int i) {
        // Sanity check - wrap around - won't happen but better to close connection that give incorrect behaviour
        if (i == Integer.MIN_VALUE) {
            String msg = "int wrapped!";
            logAndClose(msg);
            throw new IllegalStateException(msg);
        }
    }

    protected void checkWrap(long l) {
        // Sanity check - wrap around - won't happen but better to close connection that give incorrect behaviour
        if (l == Long.MIN_VALUE) {
            String msg = "long wrapped!";
            logAndClose(msg);
            throw new IllegalStateException(msg);
        }
    }

    protected void writeResponseOrdered0(Buffer buff) {
        transportConnection.write(buff);
        expectedRespNo++;
        checkWrap(expectedRespNo);
    }

    protected void checkAuthorised() {
        if (!authorised) {
            logger.error("Attempt to use unauthorised connection.");
        }
    }

    protected void logAndClose(String errMsg) {
        logger.warn(errMsg + ". connection will be closed");
        close();
    }

    // Sanity check - this should always be executed using the correct context
    protected void checkContext() {
        if (Vertx.currentContext() != context) {
            throw new IllegalStateException("Wrong context!");
        }
    }

    protected void removeQueryState(int queryID) {
        queryExecutions.remove(queryID);
    }

    protected void close() {
        authorised = false;
        transportConnection.close();
        server.removeConnection(this);
        for (QueryExecution queryExecution : queryExecutions.values()) {
            queryExecution.close();
        }
    }

    protected ServerImpl server() {
        return server;
    }

    private void handleCloseUnsubscribeSub(BsonObject frame, boolean unsubscribe) {
        checkContext();
        checkAuthorised();
        Integer subID = frame.getInteger(Protocol.UNSUBSCRIBE_SUBID);
        if (subID == null) {
            logAndClose("No subID in UNSUBSCRIBE");
            return;
        }
        SubscriptionImpl subscription = subscriptionMap.remove(subID);
        if (subscription == null) {
            logAndClose("Invalid subID in UNSUBSCRIBE");
            return;
        }
        subscription.close();
        if (unsubscribe) {
            subscription.unsubscribe();
        }
        BsonObject resp = new BsonObject();
        resp.put(Protocol.RESPONSE_OK, true);
        writeResponse(Protocol.RESPONSE_FRAME, resp, getWriteSeq());
    }

    private static final class WriteHolder implements Comparable<WriteHolder> {
        final long order;
        final Buffer buff;

        WriteHolder(long order, Buffer buff) {
            this.order = order;
            this.buff = buff;
        }

        @Override
        public int compareTo(WriteHolder other) {
            return Long.compare(this.order, other.order);
        }
    }


}
