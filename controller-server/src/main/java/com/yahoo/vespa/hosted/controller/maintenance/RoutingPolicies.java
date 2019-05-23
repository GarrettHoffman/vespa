// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.LoadBalancer;
import com.yahoo.vespa.hosted.controller.api.integration.dns.AliasTarget;
import com.yahoo.vespa.hosted.controller.api.integration.dns.Record;
import com.yahoo.vespa.hosted.controller.api.integration.dns.RecordData;
import com.yahoo.vespa.hosted.controller.api.integration.dns.RecordName;
import com.yahoo.vespa.hosted.controller.application.Endpoint;
import com.yahoo.vespa.hosted.controller.application.RoutingId;
import com.yahoo.vespa.hosted.controller.application.RoutingPolicy;
import com.yahoo.vespa.hosted.controller.dns.NameServiceQueue.Priority;
import com.yahoo.vespa.hosted.controller.persistence.CuratorDb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Updates routing policies and their associated DNS records based on an deployment's load balancers.
 *
 * @author mortent
 * @author mpolden
 */
public class RoutingPolicies {

    private final Controller controller;
    private final CuratorDb db;

    public RoutingPolicies(Controller controller) {
        this.controller = Objects.requireNonNull(controller, "controller must be non-null");
        this.db = controller.curator();
        try (Lock lock = db.lockRoutingPolicies()) { // Update serialized format
            for (var policy : db.readRoutingPolicies().entrySet()) {
                db.writeRoutingPolicies(policy.getKey(), policy.getValue());
            }
        }
    }


    /**
     * Refresh routing policies for application in given zone. This is idempotent and changes will only be performed if
     * load balancers for given application have changed.
     */
    public void refresh(ApplicationId application, ZoneId zone) {
        var lbs = new LoadBalancers(application, zone, controller.applications().configServer()
                                                                 .getLoadBalancers(application, zone));
        removeObsoleteEndpointsFromDns(lbs);
        storePoliciesOf(lbs);
        removeObsoletePolicies(lbs);
        registerEndpointsInDns(lbs);
    }

    /** Create global endpoints for given route, if any */
    private void registerEndpointsInDns(LoadBalancers loadBalancers) {
        try (Lock lock = db.lockRoutingPolicies()) {
            Map<RoutingId, List<RoutingPolicy>> routingTable = routingTableFrom(db.readRoutingPolicies(loadBalancers.application));

            // Create DNS record for each routing ID
            for (Map.Entry<RoutingId, List<RoutingPolicy>> routeEntry : routingTable.entrySet()) {
                Endpoint endpoint = RoutingPolicy.endpointOf(routeEntry.getKey().application(), routeEntry.getKey().rotation(),
                                                             controller.system());
                Set<AliasTarget> targets = routeEntry.getValue()
                                                     .stream()
                                                     .filter(policy -> policy.dnsZone().isPresent())
                                                     .map(policy -> new AliasTarget(policy.canonicalName(),
                                                                                    policy.dnsZone().get(),
                                                                                    policy.zone()))
                                                     .collect(Collectors.toSet());
                controller.nameServiceForwarder().createAlias(RecordName.from(endpoint.dnsName()), targets, Priority.normal);
            }
        }
    }

    /** Store routing policies for given route */
    private void storePoliciesOf(LoadBalancers loadBalancers) {
        try (Lock lock = db.lockRoutingPolicies()) {
            Set<RoutingPolicy> policies = new LinkedHashSet<>(db.readRoutingPolicies(loadBalancers.application));
            for (LoadBalancer loadBalancer : loadBalancers.list) {
                RoutingPolicy policy = createPolicy(loadBalancers.application, loadBalancers.zone, loadBalancer);
                if (!policies.add(policy)) {
                    policies.remove(policy);
                    policies.add(policy);
                }
            }
            db.writeRoutingPolicies(loadBalancers.application, policies);
        }
    }

    /** Create a policy for given load balancer and register a CNAME for it */
    private RoutingPolicy createPolicy(ApplicationId application, ZoneId zone, LoadBalancer loadBalancer) {
        RoutingPolicy routingPolicy = new RoutingPolicy(application, loadBalancer.cluster(), zone,
                                                        loadBalancer.hostname(), loadBalancer.dnsZone(),
                                                        loadBalancer.rotations());
        RecordName name = RecordName.from(routingPolicy.endpointIn(controller.system()).dnsName());
        RecordData data = RecordData.fqdn(loadBalancer.hostname().value());
        controller.nameServiceForwarder().createCname(name, data, Priority.normal);
        return routingPolicy;
    }

    /** Remove obsolete policies for given route and their CNAME records */
    private void removeObsoletePolicies(LoadBalancers loadBalancers) {
        try (Lock lock = db.lockRoutingPolicies()) {
            var allPolicies = new LinkedHashSet<>(db.readRoutingPolicies(loadBalancers.application));
            var removalCandidates = new HashSet<>(allPolicies);
            var activeLoadBalancers = loadBalancers.list.stream()
                                                        .map(LoadBalancer::hostname)
                                                        .collect(Collectors.toSet());
            // Remove active load balancers and irrelevant zones from candidates
            removalCandidates.removeIf(policy -> activeLoadBalancers.contains(policy.canonicalName()) ||
                                                 !policy.zone().equals(loadBalancers.zone));
            for (var policy : removalCandidates) {
                var dnsName = policy.endpointIn(controller.system()).dnsName();
                controller.nameServiceForwarder().removeRecords(Record.Type.CNAME, RecordName.from(dnsName), Priority.normal);
                allPolicies.remove(policy);
            }
            db.writeRoutingPolicies(loadBalancers.application, allPolicies);
        }
    }

    /** Remove unreferenced global endpoints for given route from DNS */
    private void removeObsoleteEndpointsFromDns(LoadBalancers loadBalancers) {
        try (Lock lock = db.lockRoutingPolicies()) {
            var zonePolicies = db.readRoutingPolicies(loadBalancers.application).stream()
                                 .filter(policy -> policy.zone().equals(loadBalancers.zone))
                                 .collect(Collectors.toUnmodifiableSet());
            var removalCandidates = routingTableFrom(zonePolicies).keySet();
            var activeRoutingIds = routingIdsFrom(loadBalancers.list);
            removalCandidates.removeAll(activeRoutingIds);
            for (var id : removalCandidates) {
                Endpoint endpoint = RoutingPolicy.endpointOf(id.application(), id.rotation(), controller.system());
                controller.nameServiceForwarder().removeRecords(Record.Type.ALIAS, RecordName.from(endpoint.dnsName()), Priority.normal);
            }
        }
    }

    /** Compute routing IDs from given load balancers */
    private static Set<RoutingId> routingIdsFrom(List<LoadBalancer> loadBalancers) {
        Set<RoutingId> routingIds = new LinkedHashSet<>();
        for (var loadBalancer : loadBalancers) {
            for (var rotation : loadBalancer.rotations()) {
                routingIds.add(new RoutingId(loadBalancer.application(), rotation));
            }
        }
        return Collections.unmodifiableSet(routingIds);
    }

    /** Compute a routing table from given policies */
    private static Map<RoutingId, List<RoutingPolicy>> routingTableFrom(Set<RoutingPolicy> routingPolicies) {
        var routingTable = new LinkedHashMap<RoutingId, List<RoutingPolicy>>();
        for (var policy : routingPolicies) {
            for (var rotation : policy.rotations()) {
                var id = new RoutingId(policy.owner(), rotation);
                routingTable.putIfAbsent(id, new ArrayList<>());
                routingTable.get(id).add(policy);
            }
        }
        return routingTable;
    }

    /** Load balancers for a particular deployment */
    private static class LoadBalancers {

        private final ApplicationId application;
        private final ZoneId zone;
        private final List<LoadBalancer> list;

        private LoadBalancers(ApplicationId application, ZoneId zone, List<LoadBalancer> list) {
            this.application = application;
            this.zone = zone;
            this.list = list;
        }

    }

}
