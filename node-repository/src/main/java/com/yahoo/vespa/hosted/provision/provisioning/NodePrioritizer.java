// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import java.util.logging.Level;
import com.yahoo.vespa.hosted.provision.LockedNodeList;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.node.IP;
import com.yahoo.vespa.hosted.provision.persistence.NameResolver;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Builds up data structures necessary for node prioritization. It wraps each node
 * up in a PrioritizableNode object with attributes used in sorting.
 *
 * The actual sorting/prioritization is implemented in the PrioritizableNode class as a compare method.
 *
 * @author smorgrav
 */
public class NodePrioritizer {

    private final static Logger log = Logger.getLogger(NodePrioritizer.class.getName());

    private final Map<Node, PrioritizableNode> nodes = new HashMap<>();
    private final LockedNodeList allNodes;
    private final DockerHostCapacity capacity;
    private final NodeSpec requestedNodes;
    private final ApplicationId application;
    private final ClusterSpec clusterSpec;
    private final NameResolver nameResolver;
    private final boolean isDocker;
    private final boolean isAllocatingForReplacement;
    private final boolean isTopologyChange;
    /** If set, a host can only have nodes by single tenant and does not allow in-place resizing.  */
    private final boolean allocateFully;
    private final int currentClusterSize;
    private final Set<Node> spareHosts;

    NodePrioritizer(LockedNodeList allNodes, ApplicationId application, ClusterSpec clusterSpec, NodeSpec nodeSpec,
                    int spares, int wantedGroups, NameResolver nameResolver, HostResourcesCalculator hostResourcesCalculator,
                    boolean allocateFully) {
        this.allNodes = allNodes;
        this.capacity = new DockerHostCapacity(allNodes, hostResourcesCalculator);
        this.requestedNodes = nodeSpec;
        this.clusterSpec = clusterSpec;
        this.application = application;
        this.nameResolver = nameResolver;
        this.spareHosts = findSpareHosts(allNodes, capacity, spares);
        this.allocateFully = allocateFully;

        NodeList nodesInCluster = allNodes.owner(application).type(clusterSpec.type()).cluster(clusterSpec.id());
        NodeList nonRetiredNodesInCluster = nodesInCluster.not().retired();
        long currentGroups = nonRetiredNodesInCluster.state(Node.State.active).stream()
                .flatMap(node -> node.allocation()
                        .flatMap(alloc -> alloc.membership().cluster().group().map(ClusterSpec.Group::index))
                        .stream())
                .distinct()
                .count();
        this.isTopologyChange = currentGroups != wantedGroups;

        this.currentClusterSize = (int) nonRetiredNodesInCluster.state(Node.State.active).stream()
                .map(node -> node.allocation().flatMap(alloc -> alloc.membership().cluster().group()))
                .filter(clusterSpec.group()::equals)
                .count();

        this.isAllocatingForReplacement = isReplacement(
                nodesInCluster.size(),
                nodesInCluster.state(Node.State.failed).size());
        this.isDocker = resources(requestedNodes) != null;
    }

    /**
     * Spare hosts are the two hosts in the system with the most free capacity.
     *
     * We do not count retired or inactive nodes as used capacity (as they could have been
     * moved to create space for the spare node in the first place).
     */
    private static Set<Node> findSpareHosts(LockedNodeList nodes, DockerHostCapacity capacity, int spares) {
        return nodes.asList().stream()
                    .filter(node -> node.type() == NodeType.host)
                    .filter(dockerHost -> dockerHost.state() == Node.State.active)
                    .filter(dockerHost -> capacity.freeIPs(dockerHost) > 0)
                    .sorted(capacity::compareWithoutInactive)
                    .limit(spares)
                    .collect(Collectors.toSet());
    }

    /** Returns the list of nodes sorted by PrioritizableNode::compare */
    List<PrioritizableNode> prioritize() {
        return nodes.values().stream().sorted().collect(Collectors.toList());
    }

    /**
     * Add nodes that have been previously reserved to the same application from
     * an earlier downsizing of a cluster
     */
    void addSurplusNodes(List<Node> surplusNodes) {
        for (Node node : surplusNodes) {
            PrioritizableNode nodePri = toPrioritizable(node, true, false);
            if (!nodePri.violatesSpares || isAllocatingForReplacement) {
                nodes.put(node, nodePri);
            }
        }
    }

    /** Add a node on each docker host with enough capacity for the requested flavor  */
    void addNewDockerNodes(Predicate<Node> canAllocateTenantNodeTo) {
        if ( ! isDocker) return;

        LockedNodeList candidates = allNodes
                .filter(node -> node.type() != NodeType.host || canAllocateTenantNodeTo.test(node))
                .filter(node -> node.reservedTo().isEmpty() || node.reservedTo().get().equals(application.tenant()));

        if (allocateFully) {
            Set<String> candidateHostnames = candidates.asList().stream()
                                                       .filter(node -> node.type() == NodeType.tenant)
                                                       .filter(node -> node.allocation()
                                                                           .map(a -> a.owner().tenant().equals(this.application.tenant()))
                                                                           .orElse(false))
                                                       .flatMap(node -> node.parentHostname().stream())
                                                       .collect(Collectors.toSet());

            candidates = candidates.filter(node -> candidateHostnames.contains(node.hostname()));
        }

        addNewDockerNodesOn(candidates);
    }

    private void addNewDockerNodesOn(LockedNodeList candidates) {
        NodeResources wantedResources = resources(requestedNodes);

        for (Node host : candidates) {
            boolean hostHasCapacityForWantedFlavor = capacity.hasCapacity(host, wantedResources);
            boolean conflictingCluster = allNodes.childrenOf(host).owner(application).asList().stream()
                                                 .anyMatch(child -> child.allocation().get().membership().cluster().id().equals(clusterSpec.id()));

            if (!hostHasCapacityForWantedFlavor || conflictingCluster) continue;

            log.log(Level.FINE, "Trying to add new Docker node on " + host);
            Optional<IP.Allocation> allocation;
            try {
                allocation = host.ipConfig().pool().findAllocation(allNodes, nameResolver);
                if (allocation.isEmpty()) continue; // No free addresses in this pool
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed allocating IP address on " + host.hostname(), e);
                continue;
            }

            Node newNode = Node.createDockerNode(allocation.get().addresses(),
                                                 allocation.get().hostname(),
                                                 host.hostname(),
                                                 resources(requestedNodes).with(host.flavor().resources().diskSpeed())
                                                                          .with(host.flavor().resources().storageType()),
                                                 NodeType.tenant);
            PrioritizableNode nodePri = toPrioritizable(newNode, false, true);
            if ( ! nodePri.violatesSpares || isAllocatingForReplacement) {
                log.log(Level.FINE, "Adding new Docker node " + newNode);
                nodes.put(newNode, nodePri);
            }
        }
    }

    /** Add existing nodes allocated to the application */
    void addApplicationNodes() {
        EnumSet<Node.State> legalStates = EnumSet.of(Node.State.active, Node.State.inactive, Node.State.reserved);
        allNodes.asList().stream()
                .filter(node -> node.type() == requestedNodes.type())
                .filter(node -> legalStates.contains(node.state()))
                .filter(node -> node.allocation().isPresent())
                .filter(node -> node.allocation().get().owner().equals(application))
                .map(node -> toPrioritizable(node, false, false))
                .forEach(prioritizableNode -> nodes.put(prioritizableNode.node, prioritizableNode));
    }

    /** Add nodes already provisioned, but not allocated to any application */
    void addReadyNodes() {
        allNodes.asList().stream()
                .filter(node -> node.type() == requestedNodes.type())
                .filter(node -> node.state() == Node.State.ready)
                .map(node -> toPrioritizable(node, false, false))
                .filter(n -> !n.violatesSpares || isAllocatingForReplacement)
                .forEach(prioritizableNode -> nodes.put(prioritizableNode.node, prioritizableNode));
    }

    /**
     * Convert a list of nodes to a list of node priorities. This includes finding, calculating
     * parameters to the priority sorting procedure.
     */
    private PrioritizableNode toPrioritizable(Node node, boolean isSurplusNode, boolean isNewNode) {
        PrioritizableNode.Builder builder = new PrioritizableNode.Builder(node)
                .surplusNode(isSurplusNode)
                .newNode(isNewNode);

        allNodes.parentOf(node).ifPresent(parent -> {
            NodeResources parentCapacity = capacity.freeCapacityOf(parent, false);
            builder.parent(parent).freeParentCapacity(parentCapacity);

            if (!isNewNode)
                builder.resizable(!allocateFully && requestedNodes.canResize(
                        node.flavor().resources(), parentCapacity, isTopologyChange, currentClusterSize));

            if (spareHosts.contains(parent))
                builder.violatesSpares(true);
        });

        return builder.build();
    }

    private boolean isReplacement(int nofNodesInCluster, int nodeFailedNodes) {
        if (nodeFailedNodes == 0) return false;

        return requestedNodes.fulfilledBy(nofNodesInCluster - nodeFailedNodes);
    }

    private static NodeResources resources(NodeSpec requestedNodes) {
        if ( ! (requestedNodes instanceof NodeSpec.CountNodeSpec)) return null;
        return requestedNodes.resources().get();
    }

}
