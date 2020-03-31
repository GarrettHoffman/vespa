// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "domainpart.h"
#include "session.h"
#include <vespa/vespalib/util/threadexecutor.h>
#include <vespa/vespalib/util/time.h>
#include <vespa/fastos/thread.h>
#include <chrono>

namespace search::transactionlog {

class DomainConfig {
public:
    using duration = vespalib::duration;
    DomainConfig();
    DomainConfig & setEncoding(Encoding v)          { _encoding = v; return *this; }
    DomainConfig & setPartSizeLimit(size_t v)       { _partSizeLimit = v; return *this; }
    DomainConfig & setChunkSizeLimit(size_t v)      { _chunkSizeLimit = v; return *this; }
    DomainConfig & setChunkAgeLimit(vespalib::duration v) { _chunkAgeLimit = v; return *this; }
    DomainConfig & setCompressionLevel(uint8_t v)   { _compressionLevel = v; return *this; }
    Encoding          getEncoding() const { return _encoding; }
    size_t       getPartSizeLimit() const { return _partSizeLimit; }
    size_t      getChunkSizeLimit() const { return _chunkSizeLimit; }
    duration     getChunkAgeLimit() const { return _chunkAgeLimit; }
    uint8_t   getCompressionlevel() const { return _compressionLevel; }
private:
    Encoding     _encoding;
    uint8_t      _compressionLevel;
    size_t       _partSizeLimit;
    size_t       _chunkSizeLimit;
    duration     _chunkAgeLimit;
};

struct PartInfo {
    SerialNumRange range;
    size_t numEntries;
    size_t byteSize;
    vespalib::string file;
    PartInfo(SerialNumRange range_in, size_t numEntries_in, size_t byteSize_in, vespalib::stringref file_in)
        : range(range_in),
          numEntries(numEntries_in),
          byteSize(byteSize_in),
          file(file_in)
    {}
};

struct DomainInfo {
    using DurationSeconds = std::chrono::duration<double>;
    SerialNumRange range;
    size_t numEntries;
    size_t byteSize;
    DurationSeconds maxSessionRunTime;
    std::vector<PartInfo> parts;
    DomainInfo(SerialNumRange range_in, size_t numEntries_in, size_t byteSize_in, DurationSeconds maxSessionRunTime_in)
        : range(range_in), numEntries(numEntries_in), byteSize(byteSize_in), maxSessionRunTime(maxSessionRunTime_in), parts() {}
    DomainInfo()
        : range(), numEntries(0), byteSize(0), maxSessionRunTime(), parts() {}
};

typedef std::map<vespalib::string, DomainInfo> DomainStats;

class Domain final : public FastOS_Runnable
{
public:
    using SP = std::shared_ptr<Domain>;
    using Executor = vespalib::SyncableThreadExecutor;
    Domain(const vespalib::string &name, const vespalib::string &baseDir, FastOS_ThreadPool & threadPool,
           Executor & commitExecutor, Executor & sessionExecutor, const DomainConfig & cfg,
           const common::FileHeaderContext &fileHeaderContext);

    ~Domain() override;

    DomainInfo getDomainInfo() const;
    const vespalib::string & name() const { return _name; }
    bool erase(SerialNum to);

    void commit(const Packet & packet, Writer::DoneCallback onDone);
    int visit(const Domain::SP & self, SerialNum from, SerialNum to, std::unique_ptr<Session::Destination> dest);

    SerialNum begin() const;
    SerialNum end() const;
    SerialNum getSynced() const;
    void triggerSyncNow();
    bool getMarkedDeleted() const { return _markedDeleted; }
    void markDeleted() { _markedDeleted = true; }

    size_t byteSize() const;
    size_t getNumSessions() const { return _sessions.size(); }

    int startSession(int sessionId);
    int closeSession(int sessionId);

    SerialNum findOldestActiveVisit() const;
    DomainPart::SP findPart(SerialNum s);

    static vespalib::string
    getDir(const vespalib::string & base, const vespalib::string & domain) {
        return base + "/" + domain;
    }
    vespalib::Executor::Task::UP execute(vespalib::Executor::Task::UP task) {
        return _sessionExecutor.execute(std::move(task));
    }
    uint64_t size() const;
    Domain & setConfig(const DomainConfig & cfg);
private:
    void Run(FastOS_ThreadInterface *thisThread, void *arguments) override;
    void commitIfStale(const vespalib::MonitorGuard & guard);
    void commitIfFull(const vespalib::MonitorGuard & guard);
    class Chunk {
    public:
        Chunk();
        ~Chunk();
        void add(const Packet & packet, Writer::DoneCallback onDone);
        size_t sizeBytes() const { return _data.sizeBytes(); }
        const Packet & getPacket() const { return _data; }
        vespalib::duration age() const;
    private:
        Packet                             _data;
        std::vector<Writer::DoneCallback>  _callBacks;
        vespalib::steady_time              _firstArrivalTime;
    };

    std::unique_ptr<Chunk> grabCurrentChunk(const vespalib::MonitorGuard & guard);
    void commitChunk(std::unique_ptr<Chunk> chunk, const vespalib::MonitorGuard & chunkOrderGuard);
    void doCommit(std::unique_ptr<Chunk> chunk);
    SerialNum begin(const vespalib::LockGuard & guard) const;
    SerialNum end(const vespalib::LockGuard & guard) const;
    size_t byteSize(const vespalib::LockGuard & guard) const;
    uint64_t size(const vespalib::LockGuard & guard) const;
    void cleanSessions();
    vespalib::string dir() const { return getDir(_baseDir, _name); }
    void addPart(int64_t partId, bool isLastPart);

    using SerialNumList = std::vector<SerialNum>;

    SerialNumList scanDir();

    using SessionList = std::map<int, Session::SP>;
    using DomainPartList = std::map<int64_t, DomainPart::SP>;
    using DurationSeconds = std::chrono::duration<double>;

    DomainConfig           _config;
    std::unique_ptr<Chunk> _currentChunk;
    SerialNum              _lastSerial;
    FastOS_ThreadPool    & _threadPool;
    std::unique_ptr<Executor> _singleCommiter;
    Executor             & _commitExecutor;
    Executor             & _sessionExecutor;
    std::atomic<int>       _sessionId;
    vespalib::Monitor      _syncMonitor;
    bool                   _pendingSync;
    vespalib::string       _name;
    DomainPartList         _parts;
    vespalib::Lock         _lock;
    vespalib::Monitor      _currentChunkMonitor;
    vespalib::Lock         _sessionLock;
    SessionList            _sessions;
    DurationSeconds        _maxSessionRunTime;
    vespalib::string       _baseDir;
    const common::FileHeaderContext &_fileHeaderContext;
    bool                   _markedDeleted;
    FastOS_ThreadInterface  * _self;
};

}
