// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "match_master.h"
#include "docid_range_scheduler.h"
#include "match_loop_communicator.h"
#include "match_thread.h"
#include <vespa/searchlib/attribute/attribute_operation.h>
#include <vespa/searchlib/engine/trace.h>
#include <vespa/searchlib/common/featureset.h>
#include <vespa/vespalib/util/thread_bundle.h>
#include <vespa/vespalib/data/slime/inserter.h>
#include <vespa/vespalib/data/slime/inject.h>
#include <vespa/vespalib/data/slime/cursor.h>
#include <vespa/eval/eval/tensor.h>
#include <vespa/eval/eval/tensor_engine.h>
#include <vespa/vespalib/objects/nbostream.h>

#include <vespa/log/log.h>
LOG_SETUP(".proton.matching.match_master");

namespace proton::matching {

using namespace search::fef;
using search::queryeval::SearchIterator;
using search::FeatureSet;
using search::attribute::AttributeOperation;

namespace {

struct TimedMatchLoopCommunicator : IMatchLoopCommunicator {
    IMatchLoopCommunicator &communicator;
    fastos::StopWatch rerank_time;
    TimedMatchLoopCommunicator(IMatchLoopCommunicator &com) : communicator(com) {}
    double estimate_match_frequency(const Matches &matches) override {
        return communicator.estimate_match_frequency(matches);
    }
    Hits selectBest(SortedHitSequence sortedHits) override {
        auto result = communicator.selectBest(sortedHits);
        rerank_time = fastos::StopWatch();
        return result;
    }
    RangePair rangeCover(const RangePair &ranges) override {
        RangePair result = communicator.rangeCover(ranges);
        rerank_time.stop();
        return result;
    }
};

DocidRangeScheduler::UP
createScheduler(uint32_t numThreads, uint32_t numSearchPartitions, uint32_t numDocs)
{
    if (numSearchPartitions == 0) {
        return std::make_unique<AdaptiveDocidRangeScheduler>(numThreads, 1, numDocs);
    }
    if (numSearchPartitions <= numThreads) {
        return std::make_unique<PartitionDocidRangeScheduler>(numThreads, numDocs);
    }
    return std::make_unique<TaskDocidRangeScheduler>(numThreads, numSearchPartitions, numDocs);
}

} // namespace proton::matching::<unnamed>

ResultProcessor::Result::UP
MatchMaster::match(search::engine::Trace & trace,
                   const MatchParams &params,
                   vespalib::ThreadBundle &threadBundle,
                   const MatchToolsFactory &mtf,
                   ResultProcessor &resultProcessor,
                   uint32_t distributionKey,
                   uint32_t numSearchPartitions)
{
    fastos::StopWatch query_latency_time;
    vespalib::DualMergeDirector mergeDirector(threadBundle.size());
    MatchLoopCommunicator communicator(threadBundle.size(), params.heapSize, mtf.createDiversifier(params.heapSize));
    TimedMatchLoopCommunicator timedCommunicator(communicator);
    DocidRangeScheduler::UP scheduler = createScheduler(threadBundle.size(), numSearchPartitions, params.numDocs);

    std::vector<MatchThread::UP> threadState;
    std::vector<vespalib::Runnable*> targets;
    for (size_t i = 0; i < threadBundle.size(); ++i) {
        IMatchLoopCommunicator &com = (i == 0)
                ? static_cast<IMatchLoopCommunicator&>(timedCommunicator)
                : static_cast<IMatchLoopCommunicator&>(communicator);
        threadState.emplace_back(std::make_unique<MatchThread>(i, threadBundle.size(), params, mtf, com, *scheduler,
                                                               resultProcessor, mergeDirector, distributionKey,
                                                               trace.getRelativeTime(), trace.getLevel()));
        targets.push_back(threadState.back().get());
    }
    resultProcessor.prepareThreadContextCreation(threadBundle.size());
    threadBundle.run(targets);
    ResultProcessor::Result::UP reply = resultProcessor.makeReply(threadState[0]->extract_result());
    query_latency_time.stop();
    double query_time_s = query_latency_time.elapsed().sec();
    double rerank_time_s = timedCommunicator.rerank_time.elapsed().sec();
    double match_time_s = 0.0;
    std::unique_ptr<vespalib::slime::Inserter> inserter;
    if (trace.shouldTrace(4)) {
        inserter = std::make_unique<vespalib::slime::ArrayInserter>(trace.createCursor("match_threads").setArray("threads"));
    }
    for (size_t i = 0; i < threadState.size(); ++i) {
        const MatchThread & matchThread = *threadState[i];
        match_time_s = std::max(match_time_s, matchThread.get_match_time());
        _stats.merge_partition(matchThread.get_thread_stats(), i);
        if (inserter && matchThread.getTrace().hasTrace()) {
            vespalib::slime::inject(matchThread.getTrace().getRoot(), *inserter);
        }
    }
    _stats.queryLatency(query_time_s);
    _stats.matchTime(match_time_s - rerank_time_s);
    _stats.rerankTime(rerank_time_s);
    _stats.groupingTime(query_time_s - match_time_s);
    _stats.queries(1);
    if (mtf.match_limiter().was_limited()) {
        _stats.limited_queries(1);        
    }
    return reply;
}

}
