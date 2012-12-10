/*
 * Copyright 2004-present Facebook. All Rights Reserved.
 */
package com.facebook.presto.server;

import com.facebook.presto.execution.QueryInfo;
import com.facebook.presto.execution.TaskInfo;
import com.facebook.presto.operator.Operator;
import com.facebook.presto.operator.PageIterator;
import com.facebook.presto.operator.PageIterators;
import com.facebook.presto.tuple.TupleInfo;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import io.airlift.http.client.FullJsonResponseHandler.JsonResponse;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.Request;
import io.airlift.json.JsonCodec;

import javax.annotation.concurrent.ThreadSafe;
import javax.ws.rs.core.Response.Status;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.airlift.http.client.FullJsonResponseHandler.createFullJsonResponseHandler;
import static io.airlift.http.client.HttpUriBuilder.uriBuilderFrom;
import static io.airlift.http.client.Request.Builder.prepareDelete;
import static io.airlift.http.client.Request.Builder.prepareGet;
import static io.airlift.http.client.Request.Builder.preparePost;
import static io.airlift.http.client.StaticBodyGenerator.createStaticBodyGenerator;
import static io.airlift.http.client.StatusResponseHandler.createStatusResponseHandler;

@ThreadSafe
public class HttpQueryClient
{
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final URI queryLocation;
    private final JsonCodec<QueryInfo> queryInfoCodec;
    private final JsonCodec<TaskInfo> taskInfoCodec;
    private final List<TupleInfo> tupleInfos;

    public HttpQueryClient(String query,
            URI coordinatorLocation,
            HttpClient httpClient,
            ExecutorService executor,
            JsonCodec<QueryInfo> queryInfoCodec,
            JsonCodec<TaskInfo> taskInfoCodec)
    {
        checkNotNull(query, "query is null");
        checkNotNull(coordinatorLocation, "coordinatorLocation is null");
        checkNotNull(httpClient, "httpClient is null");
        checkNotNull(executor, "executor is null");
        checkNotNull(queryInfoCodec, "queryInfoCodec is null");
        checkNotNull(taskInfoCodec, "taskInfoCodec is null");

        this.httpClient = httpClient;
        this.executor = executor;
        this.queryInfoCodec = queryInfoCodec;
        this.taskInfoCodec = taskInfoCodec;

        Request.Builder requestBuilder = preparePost()
                .setUri(coordinatorLocation)
                .setBodyGenerator(createStaticBodyGenerator(query, Charsets.UTF_8));

        Request request = requestBuilder.build();

        JsonResponse<QueryInfo> response = httpClient.execute(request, createFullJsonResponseHandler(queryInfoCodec));
        Preconditions.checkState(response.getStatusCode() == 201,
                "Expected response code to be 201, but was %s: %s",
                response.getStatusCode(),
                response.getStatusMessage());
        String location = response.getHeader("Location");
        Preconditions.checkState(location != null);
        QueryInfo queryInfo = response.getValue();
        tupleInfos = queryInfo.getTupleInfos();

        this.queryLocation = URI.create(location);
    }

    public QueryInfo getQueryInfo()
    {
        URI statusUri = uriBuilderFrom(queryLocation).build();
        JsonResponse<QueryInfo> response = httpClient.execute(prepareGet().setUri(statusUri).build(), createFullJsonResponseHandler(queryInfoCodec));

        if (response.getStatusCode() == Status.GONE.getStatusCode()) {
            // query has failed, been deleted, or something, and is no longer being tracked by the server
            return null;
        }

        Preconditions.checkState(response.getStatusCode() == Status.OK.getStatusCode(),
                "Expected response code to be 201, but was %s: %s",
                response.getStatusCode(),
                response.getStatusMessage());

        QueryInfo queryInfo = response.getValue();
        return queryInfo;
    }

    public Operator getResultsOperator()
    {
        QueryInfo queryInfo = getQueryInfo();
        if (queryInfo == null || queryInfo.getOutputStage() == null) {
            return new Operator()
            {
                @Override
                public int getChannelCount()
                {
                    return 0;
                }

                @Override
                public List<TupleInfo> getTupleInfos()
                {
                    return ImmutableList.of();
                }

                @Override
                public PageIterator iterator()
                {
                    return PageIterators.emptyIterator(tupleInfos);
                }
            };
        }

        List<TaskInfo> outputStage = queryInfo.getOutputStage().getTasks();
        return new QueryDriversOperator(10, Iterables.transform(outputStage, new Function<TaskInfo, QueryDriverProvider>()
        {
            @Override
            public QueryDriverProvider apply(TaskInfo taskInfo)
            {
                Preconditions.checkState(taskInfo.getOutputBufferStates().size() == 1,
                        "Expected a single output buffer for task %s, but found %s",
                        taskInfo.getTaskId(),
                        taskInfo.getOutputBufferStates());

                URI taskUri = uriBuilderFrom(taskInfo.getSelf()).replacePath("/v1/task").appendPath(taskInfo.getTaskId()).build();
                return new HttpTaskClient(taskInfo.getTaskId(),
                        taskUri,
                        Iterables.getOnlyElement(taskInfo.getOutputBufferStates().keySet()),
                        taskInfo.getTupleInfos(),
                        httpClient,
                        executor,
                        taskInfoCodec);
            }
        }));
    }

    public void destroy()
    {
        try {
            Request.Builder requestBuilder = prepareDelete().setUri(queryLocation);
            Request request = requestBuilder.build();
            httpClient.execute(request, createStatusResponseHandler());
        }
        catch (RuntimeException ignored) {
        }
    }
}
