// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.session;

import java.util.logging.Level;
import com.yahoo.text.Utf8;
import com.yahoo.vespa.config.server.ReloadHandler;
import com.yahoo.vespa.config.server.monitoring.MetricUpdater;
import com.yahoo.vespa.curator.Curator;
import org.apache.curator.framework.recipes.cache.ChildData;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Watches one particular session (/config/v2/tenants/&lt;tenantName&gt;/sessions/&lt;n&gt;/sessionState in ZooKeeper)
 * The session must be in the session repo.
 *
 * @author Vegard Havdal
 */
public class RemoteSessionStateWatcher {

    private static final Logger log = Logger.getLogger(RemoteSessionStateWatcher.class.getName());
    // One thread pool for all instances of this class

    private final Curator.FileCache fileCache;
    private final ReloadHandler reloadHandler;
    private final RemoteSession session;
    private final MetricUpdater metrics;
    private final Executor zkWatcherExecutor;

    RemoteSessionStateWatcher(Curator.FileCache fileCache,
                              ReloadHandler reloadHandler,
                              RemoteSession session,
                              MetricUpdater metrics,
                              Executor zkWatcherExecutor) {
        this.fileCache = fileCache;
        this.reloadHandler = reloadHandler;
        this.session = session;
        this.metrics = metrics;
        this.fileCache.start();
        this.fileCache.addListener(this::nodeChanged);
        this.zkWatcherExecutor = zkWatcherExecutor;
    }

    private void sessionChanged(Session.Status status) {
        log.log(LogLevel.DEBUG, session.logPre() + "Session change: Remote session " + session.getSessionId() + " changed status to " + status);

        // valid for NEW -> PREPARE transitions, not ACTIVATE -> PREPARE.
        if (status.equals(Session.Status.PREPARE)) {
            log.log(LogLevel.DEBUG, session.logPre() + "Loading prepared session: " + session.getSessionId());
            session.loadPrepared();
        } else if (status.equals(Session.Status.ACTIVATE)) {
            session.makeActive(reloadHandler);
        } else if (status.equals(Session.Status.DEACTIVATE)) {
            session.deactivate();
        } else if (status.equals(Session.Status.DELETE)) {
            session.deactivate();
        }
    }

    public long getSessionId() {
        return session.getSessionId();
    }

    public void close() {
        try {
            fileCache.close();
        } catch (Exception e) {
            log.log(LogLevel.WARNING, "Exception when closing watcher", e);
        }
    }

    private void nodeChanged() {
        zkWatcherExecutor.execute(() -> {
            try {
                ChildData node = fileCache.getCurrentData();
                if (node != null) {
                    sessionChanged(Session.Status.parse(Utf8.toString(node.getData())));
                }
            } catch (Exception e) {
                log.log(LogLevel.WARNING, session.logPre() + "Error handling session changed for session " + getSessionId(), e);
                metrics.incSessionChangeErrors();
            }
        });
    }

}
