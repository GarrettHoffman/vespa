// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationLockException;
import com.yahoo.config.provision.Deployer;
import com.yahoo.config.provision.Deployment;
import com.yahoo.config.provision.TransientException;
import com.yahoo.jdisc.Metric;
import java.util.logging.Level;
import com.yahoo.transaction.Mutex;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.yolean.Exceptions;

import java.io.Closeable;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * A wrapper of a deployment suitable for maintenance.
 * This is a single-use, single-thread object.
 *
 * @author bratseth
 */
class MaintenanceDeployment implements Closeable {

    private static final Logger log = Logger.getLogger(MaintenanceDeployment.class.getName());

    private final ApplicationId application;
    private final Metric metric;
    private final Optional<Mutex> lock;
    private final Optional<Deployment> deployment;

    private boolean closed = false;

    public MaintenanceDeployment(ApplicationId application,
                                 Deployer deployer,
                                 Metric metric,
                                 NodeRepository nodeRepository) {
        this.application = application;
        this.metric = metric;
        Optional<Mutex> lock = tryLock(application, nodeRepository);
        try {
            deployment = tryDeployment(lock, application, deployer, nodeRepository);
            this.lock = lock;
            lock = Optional.empty();
        } finally {
            lock.ifPresent(Mutex::close);
        }
    }

    /** Return whether this is - as yet - functional and can be used to carry out the deployment */
    public boolean isValid() {
        return deployment.isPresent();
    }

    /**
     * Returns the application lock held by this, or empty if it is not held.
     *
     * @throws IllegalStateException id this is called when closed
     */
    public Optional<Mutex> applicationLock() {
        if (closed) throw new IllegalStateException(this + " is closed");
        return lock;
    }

    public boolean prepare() {
        return doStep(() -> deployment.get().prepare());
    }

    public boolean activate() {
        return doStep(() -> deployment.get().activate());
    }

    private boolean doStep(Runnable action) {
        if (closed) throw new IllegalStateException(this + "' is closed");
        if ( ! isValid()) return false;
        try {
            action.run();
            return true;
        } catch (TransientException e) {
            metric.add("maintenanceDeployment.transientFailure", 1, metric.createContext(Map.of()));
            log.log(Level.INFO, "Failed to maintenance deploy " + application + " with a transient error: " +
                                   Exceptions.toMessageString(e));
            return false;
        } catch (RuntimeException e) {
            metric.add("maintenanceDeployment.failure", 1, metric.createContext(Map.of()));
            log.log(LogLevel.WARNING, "Exception on maintenance deploy of " + application, e);
            return false;
        }
    }

    private Optional<Mutex> tryLock(ApplicationId application, NodeRepository nodeRepository) {
        try {
            // Use a short lock to avoid interfering with change deployments
            return Optional.of(nodeRepository.lock(application, Duration.ofSeconds(1)));
        }
        catch (ApplicationLockException e) {
            return Optional.empty();
        }
    }

    private Optional<Deployment> tryDeployment(Optional<Mutex> lock,
                                               ApplicationId application,
                                               Deployer deployer,
                                               NodeRepository nodeRepository) {
        if (lock.isEmpty()) return Optional.empty();
        if (nodeRepository.getNodes(application, Node.State.active).isEmpty()) return Optional.empty();
        return deployer.deployFromLocalActive(application);
    }

    @Override
    public void close() {
        lock.ifPresent(l -> l.close());
        closed = true;
    }

    @Override
    public String toString() {
        return "deployment of " + application;
    }

}
