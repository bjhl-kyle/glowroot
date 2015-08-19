/*
 * Copyright 2011-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.ui;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import com.google.common.primitives.Longs;
import org.immutables.value.Value;

import org.glowroot.common.live.LiveTraceRepository;
import org.glowroot.common.repo.AggregateRepository;
import org.glowroot.common.repo.AggregateRepository.OverallSummary;
import org.glowroot.common.repo.AggregateRepository.OverviewAggregate;
import org.glowroot.common.repo.AggregateRepository.PercentileAggregate;
import org.glowroot.common.repo.AggregateRepository.TransactionSummary;
import org.glowroot.common.repo.AggregateRepository.TransactionSummaryQuery;
import org.glowroot.common.repo.AggregateRepository.TransactionSummarySortOrder;
import org.glowroot.common.repo.ImmutableTransactionSummaryQuery;
import org.glowroot.common.repo.LazyHistogram;
import org.glowroot.common.repo.MutableProfileNode;
import org.glowroot.common.repo.MutableQuery;
import org.glowroot.common.repo.MutableTimerNode;
import org.glowroot.common.repo.Result;
import org.glowroot.common.repo.TraceRepository;
import org.glowroot.common.repo.Utils;
import org.glowroot.common.repo.helper.JsonMarshaller;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.ui.AggregateMerging.PercentileMergedAggregate;
import org.glowroot.ui.AggregateMerging.ThreadInfoAggregate;
import org.glowroot.ui.AggregateMerging.TimerMergedAggregate;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class TransactionJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();
    private static final double MICROSECONDS_PER_MILLISECOND = 1000.0;

    private final TransactionCommonService transactionCommonService;
    private final TraceRepository traceRepository;
    private final LiveTraceRepository liveTraceRepository;
    private final AggregateRepository aggregateRepository;
    private final Clock clock;

    TransactionJsonService(TransactionCommonService transactionCommonService,
            TraceRepository traceRepository, LiveTraceRepository liveTraceRepository,
            AggregateRepository aggregateRepository, Clock clock) {
        this.transactionCommonService = transactionCommonService;
        this.traceRepository = traceRepository;
        this.liveTraceRepository = liveTraceRepository;
        this.aggregateRepository = aggregateRepository;
        this.clock = clock;
    }

    @GET("/backend/transaction/average")
    String getOverview(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);

        long liveCaptureTime = clock.currentTimeMillis();
        List<OverviewAggregate> overviewAggregates =
                transactionCommonService.getOverviewAggregates(request.transactionType(),
                        request.transactionName(), request.from(), request.to(), liveCaptureTime);
        List<DataSeries> dataSeriesList = getDataSeriesForTimersChart(request, overviewAggregates);
        Map<Long, Long> transactionCounts = getTransactionCounts(overviewAggregates);
        if (!overviewAggregates.isEmpty()
                && overviewAggregates.get(0).captureTime() == request.from()) {
            // the left most aggregate is not really in the requested interval since it is for
            // prior capture times
            overviewAggregates = overviewAggregates.subList(1, overviewAggregates.size());
        }
        TimerMergedAggregate timerMergedAggregate =
                AggregateMerging.getTimerMergedAggregate(overviewAggregates);
        ThreadInfoAggregate threadInfoAggregate =
                AggregateMerging.getThreadInfoAggregate(overviewAggregates);

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeriesList);
        jg.writeObjectField("transactionCounts", transactionCounts);
        jg.writeObjectField("mergedAggregate", timerMergedAggregate);
        if (!threadInfoAggregate.isEmpty()) {
            jg.writeObjectField("threadInfoAggregate", threadInfoAggregate);
        }
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/percentiles")
    String getPercentiles(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);

        long liveCaptureTime = clock.currentTimeMillis();
        List<PercentileAggregate> percentileAggregates =
                transactionCommonService.getPercentileAggregates(request.transactionType(),
                        request.transactionName(), request.from(), request.to(), liveCaptureTime);
        List<DataSeries> dataSeriesList = getDataSeriesForPercentileChart(request,
                percentileAggregates, request.percentile());
        Map<Long, Long> transactionCounts = getTransactionCounts2(percentileAggregates);
        if (!percentileAggregates.isEmpty()
                && percentileAggregates.get(0).captureTime() == request.from()) {
            // the left most aggregate is not really in the requested interval since it is for
            // prior capture times
            percentileAggregates = percentileAggregates.subList(1, percentileAggregates.size());
        }
        PercentileMergedAggregate mergedAggregate = AggregateMerging
                .getPercentileMergedAggregate(percentileAggregates, request.percentile());

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeriesList);
        jg.writeObjectField("transactionCounts", transactionCounts);
        jg.writeObjectField("mergedAggregate", mergedAggregate);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/profile")
    String getProfile(String queryString) throws Exception {
        TransactionProfileRequest request =
                QueryStrings.decode(queryString, TransactionProfileRequest.class);
        MutableProfileNode profile = transactionCommonService.getMergedProfile(
                request.transactionType(), request.transactionName(), request.from(), request.to(),
                request.include(), request.exclude(), request.truncateLeafPercentage());
        if (profile.sampleCount() == 0 && request.include().isEmpty() && request.exclude().isEmpty()
                && transactionCommonService.shouldHaveProfile(request.transactionType(),
                        request.transactionName(), request.from(), request.to())) {
            return "{\"overwritten\":true}";
        }
        return JsonMarshaller.marshal(profile);
    }

    @GET("/backend/transaction/queries")
    String getQueries(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);
        Map<String, List<MutableQuery>> queries = transactionCommonService.getMergedQueries(
                request.transactionType(), request.transactionName(), request.from(), request.to());
        List<Query> queryList = Lists.newArrayList();
        for (Entry<String, List<MutableQuery>> entry : queries.entrySet()) {
            List<MutableQuery> queriesForQueryType = entry.getValue();
            for (MutableQuery aggregateQuery : queriesForQueryType) {
                queryList.add(ImmutableQuery.builder()
                        .queryType(entry.getKey())
                        .queryText(aggregateQuery.queryText())
                        .totalMicros(aggregateQuery.totalMicros())
                        .executionCount(aggregateQuery.executionCount())
                        .totalRows(aggregateQuery.totalRows())
                        .build());
            }
        }
        Collections.sort(queryList, new Comparator<Query>() {
            @Override
            public int compare(@Nullable Query left, @Nullable Query right) {
                checkNotNull(left);
                checkNotNull(right);
                // sort descending
                return Longs.compare(right.totalMicros(), left.totalMicros());
            }
        });
        if (queryList.isEmpty()
                && transactionCommonService.shouldHaveQueries(request.transactionType(),
                        request.transactionName(), request.from(), request.to())) {
            return "{\"overwritten\":true}";
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeObject(queryList);
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/summaries")
    String getSummaries(String queryString) throws Exception {
        TransactionSummaryRequest request =
                QueryStrings.decode(queryString, TransactionSummaryRequest.class);

        OverallSummary overallSummary = transactionCommonService
                .readOverallSummary(request.transactionType(), request.from(), request.to());

        TransactionSummaryQuery query = ImmutableTransactionSummaryQuery.builder()
                .transactionType(request.transactionType())
                .from(request.from())
                .to(request.to())
                .sortOrder(request.sortOrder())
                .limit(request.limit())
                .build();
        Result<TransactionSummary> queryResult =
                transactionCommonService.readTransactionSummaries(query);

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeFieldName("overall");
        jg.writeObject(overallSummary);
        jg.writeFieldName("transactions");
        jg.writeObject(queryResult.records());
        jg.writeBooleanField("moreAvailable", queryResult.moreAvailable());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/tab-bar-data")
    String getTabBarData(String queryString) throws Exception {
        TransactionDataRequest request =
                QueryStrings.decode(queryString, TransactionDataRequest.class);

        String transactionName = request.transactionName();
        long traceCount;
        if (transactionName == null) {
            traceCount = traceRepository.readOverallSlowCount(request.transactionType(),
                    request.from(), request.to());
        } else {
            traceCount = traceRepository.readTransactionSlowCount(request.transactionType(),
                    transactionName, request.from(), request.to());
        }
        boolean includeActiveTraces = shouldIncludeActiveTraces(request);
        if (includeActiveTraces) {
            traceCount += liveTraceRepository.getMatchingTraceCount(request.transactionType(),
                    request.transactionName());
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeNumberField("traceCount", traceCount);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/transaction/flame-graph")
    String getFlameGraph(String queryString) throws Exception {
        FlameGraphRequest request = QueryStrings.decode(queryString, FlameGraphRequest.class);
        MutableProfileNode profile = transactionCommonService.getMergedProfile(
                request.transactionType(), request.transactionName(), request.from(), request.to(),
                request.include(), request.exclude(), request.truncateLeafPercentage());
        MutableProfileNode interestingNode = profile;
        while (interestingNode.hasOneChildNode()) {
            interestingNode = interestingNode.getOnlyChildNode();
        }
        if (interestingNode.isEmpty()) {
            // only a single branch through entire tree
            interestingNode = profile;
        }
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectFieldStart("");
        jg.writeNumberField("svUnique", 0);
        jg.writeNumberField("svTotal", interestingNode.sampleCount());
        jg.writeObjectFieldStart("svChildren");
        writeFlameGraphNode(interestingNode, jg);
        jg.writeEndObject();
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    private Map<Long, Long> getTransactionCounts(List<OverviewAggregate> overviewAggregates) {
        Map<Long, Long> transactionCounts = Maps.newHashMap();
        for (OverviewAggregate overviewAggregate : overviewAggregates) {
            transactionCounts.put(overviewAggregate.captureTime(),
                    overviewAggregate.transactionCount());
        }
        return transactionCounts;
    }

    private Map<Long, Long> getTransactionCounts2(List<PercentileAggregate> percentileAggregates) {
        Map<Long, Long> transactionCounts = Maps.newHashMap();
        for (PercentileAggregate percentileAggregate : percentileAggregates) {
            transactionCounts.put(percentileAggregate.captureTime(),
                    percentileAggregate.transactionCount());
        }
        return transactionCounts;
    }

    private List<DataSeries> getDataSeriesForPercentileChart(TransactionDataRequest request,
            List<PercentileAggregate> percentileAggregates, List<Double> percentiles)
                    throws Exception {
        if (percentileAggregates.isEmpty()) {
            return Lists.newArrayList();
        }
        DataSeriesHelper dataSeriesHelper = new DataSeriesHelper(clock,
                aggregateRepository.getDataPointIntervalMillis(request.from(), request.to()));
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (double percentile : percentiles) {
            dataSeriesList
                    .add(new DataSeries(Utils.getPercentileWithSuffix(percentile) + " percentile"));
        }
        PercentileAggregate lastPercentileAggregate = null;
        for (PercentileAggregate percentileAggregate : percentileAggregates) {
            if (lastPercentileAggregate == null) {
                // first aggregate
                dataSeriesHelper.addInitialUpslopeIfNeeded(request.from(),
                        percentileAggregate.captureTime(), dataSeriesList, null);
            } else {
                dataSeriesHelper.addGapIfNeeded(lastPercentileAggregate.captureTime(),
                        percentileAggregate.captureTime(), dataSeriesList, null);
            }
            lastPercentileAggregate = percentileAggregate;
            LazyHistogram histogram = percentileAggregate.histogram();
            for (int i = 0; i < percentiles.size(); i++) {
                DataSeries dataSeries = dataSeriesList.get(i);
                double percentile = percentiles.get(i);
                dataSeries.add(percentileAggregate.captureTime(),
                        histogram.getValueAtPercentile(percentile) / MICROSECONDS_PER_MILLISECOND);
            }
        }
        if (lastPercentileAggregate != null) {
            dataSeriesHelper.addFinalDownslopeIfNeeded(request.to(), dataSeriesList, null,
                    lastPercentileAggregate.captureTime());
        }
        return dataSeriesList;
    }

    private List<DataSeries> getDataSeriesForTimersChart(TransactionDataRequest request,
            List<OverviewAggregate> aggregates) throws IOException {
        if (aggregates.isEmpty()) {
            return Lists.newArrayList();
        }
        List<StackedPoint> stackedPoints = Lists.newArrayList();
        for (OverviewAggregate aggregate : aggregates) {
            stackedPoints.add(StackedPoint.create(aggregate));
        }
        return getTimerDataSeries(request, stackedPoints);
    }

    private List<DataSeries> getTimerDataSeries(TransactionDataRequest request,
            List<StackedPoint> stackedPoints) {
        DataSeriesHelper dataSeriesHelper = new DataSeriesHelper(clock,
                aggregateRepository.getDataPointIntervalMillis(request.from(), request.to()));
        final int topX = 5;
        List<String> timerNames = getTopTimerNames(stackedPoints, topX + 1);
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (int i = 0; i < Math.min(timerNames.size(), topX); i++) {
            dataSeriesList.add(new DataSeries(timerNames.get(i)));
        }
        // need 'other' data series even if < topX timers in order to capture root timers,
        // e.g. time spent in 'servlet' timer but not in any nested timer
        DataSeries otherDataSeries = new DataSeries(null);
        OverviewAggregate lastOverviewAggregate = null;
        for (StackedPoint stackedPoint : stackedPoints) {
            OverviewAggregate overviewAggregate = stackedPoint.getOverviewAggregate();
            if (lastOverviewAggregate == null) {
                // first aggregate
                dataSeriesHelper.addInitialUpslopeIfNeeded(request.from(),
                        overviewAggregate.captureTime(), dataSeriesList, otherDataSeries);
            } else {
                dataSeriesHelper.addGapIfNeeded(lastOverviewAggregate.captureTime(),
                        overviewAggregate.captureTime(), dataSeriesList, otherDataSeries);
            }
            lastOverviewAggregate = overviewAggregate;
            MutableLongMap<String> stackedTimers = stackedPoint.getStackedTimers();
            long totalOtherMicros = overviewAggregate.totalMicros();
            for (DataSeries dataSeries : dataSeriesList) {
                MutableLong totalMicros = stackedTimers.get(dataSeries.getName());
                if (totalMicros == null) {
                    dataSeries.add(overviewAggregate.captureTime(), 0);
                } else {
                    // convert to average seconds
                    dataSeries.add(overviewAggregate.captureTime(),
                            (totalMicros.longValue()
                                    / (double) overviewAggregate.transactionCount())
                                    / MICROSECONDS_PER_MILLISECOND);
                    totalOtherMicros -= totalMicros.longValue();
                }
            }
            if (overviewAggregate.transactionCount() == 0) {
                otherDataSeries.add(overviewAggregate.captureTime(), 0);
            } else {
                // convert to average seconds
                otherDataSeries.add(overviewAggregate.captureTime(),
                        (totalOtherMicros / (double) overviewAggregate.transactionCount())
                                / MICROSECONDS_PER_MILLISECOND);
            }
        }
        if (lastOverviewAggregate != null) {
            dataSeriesHelper.addFinalDownslopeIfNeeded(request.to(), dataSeriesList,
                    otherDataSeries, lastOverviewAggregate.captureTime());
        }
        dataSeriesList.add(otherDataSeries);
        return dataSeriesList;
    }

    // calculate top 5 timers
    private static List<String> getTopTimerNames(List<StackedPoint> stackedPoints, int topX) {
        MutableLongMap<String> timerTotals = new MutableLongMap<String>();
        for (StackedPoint stackedPoint : stackedPoints) {
            for (Entry<String, MutableLong> entry : stackedPoint.getStackedTimers().entrySet()) {
                timerTotals.add(entry.getKey(), entry.getValue().longValue());
            }
        }
        Ordering<Entry<String, MutableLong>> valueOrdering =
                Ordering.natural().onResultOf(new Function<Entry<String, MutableLong>, Long>() {
                    @Override
                    public Long apply(@Nullable Entry<String, MutableLong> entry) {
                        checkNotNull(entry);
                        return entry.getValue().longValue();
                    }
                });
        List<String> timerNames = Lists.newArrayList();
        @SuppressWarnings("assignment.type.incompatible")
        List<Entry<String, MutableLong>> topTimerTotals =
                valueOrdering.greatestOf(timerTotals.entrySet(), topX);
        for (Entry<String, MutableLong> entry : topTimerTotals) {
            timerNames.add(entry.getKey());
        }
        return timerNames;
    }

    private boolean shouldIncludeActiveTraces(TransactionDataRequest request) {
        long currentTimeMillis = clock.currentTimeMillis();
        return (request.to() == 0 || request.to() > currentTimeMillis)
                && request.from() < currentTimeMillis;
    }

    // TODO use non-recursive algorithm to guard from stack overflow error
    private static void writeFlameGraphNode(MutableProfileNode node, JsonGenerator jg)
            throws IOException {
        StackTraceElement stackTraceElement = node.stackTraceElement();
        if (stackTraceElement == null) {
            jg.writeObjectFieldStart("");
        } else {
            jg.writeObjectFieldStart(stackTraceElement.toString());
        }
        long svUnique = node.sampleCount();
        for (MutableProfileNode childNode : node.childNodes()) {
            svUnique -= childNode.sampleCount();
        }
        jg.writeNumberField("svUnique", svUnique);
        jg.writeNumberField("svTotal", node.sampleCount());
        jg.writeObjectFieldStart("svChildren");
        for (MutableProfileNode childNode : node.childNodes()) {
            writeFlameGraphNode(childNode, jg);
        }
        jg.writeEndObject();
        jg.writeEndObject();
    }

    private static class StackedPoint {

        private final OverviewAggregate overviewAggregate;
        // stacked timer values only include time spent as a leaf node in the timer tree
        private final MutableLongMap<String> stackedTimers;

        private static StackedPoint create(OverviewAggregate overviewAggregate) throws IOException {
            MutableTimerNode syntheticRootTimer = overviewAggregate.syntheticRootTimer();
            MutableLongMap<String> stackedTimers = new MutableLongMap<String>();
            // skip synthetic root timer
            for (MutableTimerNode realRootTimer : syntheticRootTimer.childNodes()) {
                // skip real root timers
                for (MutableTimerNode topLevelTimer : realRootTimer.childNodes()) {
                    // traverse tree starting at top-level (under root) timers
                    addToStackedTimer(topLevelTimer, stackedTimers);
                }
            }
            return new StackedPoint(overviewAggregate, stackedTimers);
        }

        private StackedPoint(OverviewAggregate overviewAggregate,
                MutableLongMap<String> stackedTimers) {
            this.overviewAggregate = overviewAggregate;
            this.stackedTimers = stackedTimers;
        }

        private OverviewAggregate getOverviewAggregate() {
            return overviewAggregate;
        }

        private MutableLongMap<String> getStackedTimers() {
            return stackedTimers;
        }

        private static void addToStackedTimer(MutableTimerNode timer,
                MutableLongMap<String> stackedTimers) {
            long totalNestedMicros = 0;
            for (MutableTimerNode nestedTimer : timer.childNodes()) {
                totalNestedMicros += nestedTimer.totalMicros();
                addToStackedTimer(nestedTimer, stackedTimers);
            }
            // timer name is only null for synthetic root timer which is never passed to this method
            String timerName = checkNotNull(timer.name());
            stackedTimers.add(timerName, timer.totalMicros() - totalNestedMicros);
        }
    }

    // by using MutableLong, two operations (get/put) are not required for each increment,
    // instead just a single get is needed (except for first delta)
    //
    // not thread safe, for thread safety use guava's AtomicLongMap
    @SuppressWarnings("serial")
    private static class MutableLongMap<K> extends HashMap<K, MutableLong> {
        private void add(K key, long delta) {
            MutableLong existing = get(key);
            if (existing == null) {
                put(key, new MutableLong(delta));
            } else {
                existing.value += delta;
            }
        }
    }

    private static class MutableLong {
        private long value;
        private MutableLong(long value) {
            this.value = value;
        }
        private long longValue() {
            return value;
        }
    }

    @Value.Immutable
    interface TransactionSummaryRequest {
        long from();
        long to();
        String transactionType();
        TransactionSummarySortOrder sortOrder();
        int limit();
    }

    @Value.Immutable
    interface TransactionDataRequest {
        long from();
        long to();
        String transactionType();
        @Nullable
        String transactionName();
        // singular because this is used in query string
        ImmutableList<Double> percentile();
    }

    @Value.Immutable
    interface TransactionProfileRequest {
        long from();
        long to();
        String transactionType();
        @Nullable
        String transactionName();
        // intentionally not plural since maps from query string
        ImmutableList<String> include();
        // intentionally not plural since maps from query string
        ImmutableList<String> exclude();
        double truncateLeafPercentage();
    }

    @Value.Immutable
    interface FlameGraphRequest {
        long from();
        long to();
        String transactionType();
        @Nullable
        String transactionName();
        // intentionally not plural since maps from query string
        ImmutableList<String> include();
        // intentionally not plural since maps from query string
        ImmutableList<String> exclude();
        double truncateLeafPercentage();
    }

    @Value.Immutable
    interface Query {
        String queryType();
        String queryText();
        long totalMicros();
        long executionCount();
        long totalRows();
    }
}