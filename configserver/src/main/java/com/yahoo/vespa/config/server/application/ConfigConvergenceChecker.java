// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.application;

import ai.vespa.util.http.VespaClientBuilderFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import com.yahoo.component.AbstractComponent;
import com.yahoo.config.model.api.HostInfo;
import com.yahoo.config.model.api.PortInfo;
import com.yahoo.config.model.api.ServiceInfo;
import java.util.logging.Level;
import com.yahoo.slime.Cursor;
import com.yahoo.vespa.config.server.http.JSONResponse;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.proxy.WebResourceFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.yahoo.config.model.api.container.ContainerServiceType.CLUSTERCONTROLLER_CONTAINER;
import static com.yahoo.config.model.api.container.ContainerServiceType.CONTAINER;
import static com.yahoo.config.model.api.container.ContainerServiceType.LOGSERVER_CONTAINER;
import static com.yahoo.config.model.api.container.ContainerServiceType.QRSERVER;

/**
 * Checks for convergence of config generation for a given application.
 *
 * @author Ulf Lilleengen
 * @author hmusum
 */
public class ConfigConvergenceChecker extends AbstractComponent {

    private static final Logger log = Logger.getLogger(ConfigConvergenceChecker.class.getName());
    private static final String statePath = "/state/v1/";
    private static final String configSubPath = "config";
    private final static Set<String> serviceTypesToCheck = Set.of(
            CONTAINER.serviceName,
            QRSERVER.serviceName,
            LOGSERVER_CONTAINER.serviceName,
            CLUSTERCONTROLLER_CONTAINER.serviceName,
            "searchnode",
            "storagenode",
            "distributor"
    );

    private final StateApiFactory stateApiFactory;
    private final VespaClientBuilderFactory clientBuilderFactory = new VespaClientBuilderFactory();

    @Inject
    public ConfigConvergenceChecker() {
        this(ConfigConvergenceChecker::createStateApi);
    }

    public ConfigConvergenceChecker(StateApiFactory stateApiFactory) {
        this.stateApiFactory = stateApiFactory;
    }

    /** Check all services in given application. Returns the minimum current generation of all services */
    public ServiceListResponse servicesToCheck(Application application, URI requestUrl, Duration timeoutPerService) {
        log.log(LogLevel.DEBUG, () -> "Finding services to check config convergence for in '" + application);
        List<ServiceInfo> servicesToCheck = new ArrayList<>();
        application.getModel().getHosts()
                   .forEach(host -> host.getServices().stream()
                                        .filter(service -> serviceTypesToCheck.contains(service.getServiceType()))
                                        .forEach(service -> getStatePort(service).ifPresent(port -> servicesToCheck.add(service))));

        Map<ServiceInfo, Long> currentGenerations = getServiceGenerations(servicesToCheck, timeoutPerService);
        long currentGeneration = currentGenerations.values().stream().mapToLong(Long::longValue).min().orElse(-1);
        return new ServiceListResponse(200, currentGenerations, requestUrl, application.getApplicationGeneration(),
                                       currentGeneration);
    }

    /** Check service identified by host and port in given application */
    public ServiceResponse checkService(Application application, String hostAndPortToCheck, URI requestUrl, Duration timeout) {
        Long wantedGeneration = application.getApplicationGeneration();
        try {
            if (! hostInApplication(application, hostAndPortToCheck))
                return ServiceResponse.createHostNotFoundInAppResponse(requestUrl, hostAndPortToCheck, wantedGeneration);

            long currentGeneration = getServiceGeneration(URI.create("http://" + hostAndPortToCheck), timeout);
            boolean converged = currentGeneration >= wantedGeneration;
            return ServiceResponse.createOkResponse(requestUrl, hostAndPortToCheck, wantedGeneration, currentGeneration, converged);
        } catch (ProcessingException e) { // e.g. if we cannot connect to the service to find generation
            return ServiceResponse.createNotFoundResponse(requestUrl, hostAndPortToCheck, wantedGeneration, e.getMessage());
        } catch (Exception e) {
            return ServiceResponse.createErrorResponse(requestUrl, hostAndPortToCheck, wantedGeneration, e.getMessage());
        }
    }

    @Override
    public void deconstruct() {
        clientBuilderFactory.close();
    }

    @Path(statePath)
    public interface StateApi {
        @Path(configSubPath)
        @GET
        JsonNode config();
    }

    public interface StateApiFactory {
        StateApi createStateApi(Client client, URI serviceUri);
    }

    /** Gets service generation for a list of services (in parallel). */
    private Map<ServiceInfo, Long> getServiceGenerations(List<ServiceInfo> services, Duration timeout) {
        return services.parallelStream()
                       .collect(Collectors.toMap(service -> service,
                                                 service -> {
                                                     try {
                                                         return getServiceGeneration(URI.create("http://" + service.getHostName()
                                                                                                + ":" + getStatePort(service).get()), timeout);
                                                     }
                                                     catch (ProcessingException e) { // Cannot connect to service to determine service generation
                                                         return -1L;
                                                     }
                                                 },
                                                 (v1, v2) -> { throw new IllegalStateException("Duplicate keys for values '" + v1 + "' and '" + v2 + "'."); },
                                                 LinkedHashMap::new
                                                ));
    }

    /** Get service generation of service at given URL */
    private long getServiceGeneration(URI serviceUrl, Duration timeout) {
        Client client = createClient(timeout);
        try {
            StateApi state = stateApiFactory.createStateApi(client, serviceUrl);
            return generationFromContainerState(state.config());
        } finally {
            client.close();
        }
    }

    private boolean hostInApplication(Application application, String hostPort) {
        for (HostInfo host : application.getModel().getHosts()) {
            if (hostPort.startsWith(host.getHostname())) {
                for (ServiceInfo service : host.getServices()) {
                    for (PortInfo port : service.getPorts()) {
                        if (hostPort.equals(host.getHostname() + ":" + port.getPort())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private Client createClient(Duration timeout) {
        return clientBuilderFactory.newBuilder()
                            .register(
                                    (ClientRequestFilter) ctx ->
                                            ctx.getHeaders().put(HttpHeaders.USER_AGENT, List.of("config-convergence-checker")))
                            .property(ClientProperties.CONNECT_TIMEOUT, (int) timeout.toMillis())
                            .property(ClientProperties.READ_TIMEOUT, (int) timeout.toMillis())
                            .build();
    }

    private static Optional<Integer> getStatePort(ServiceInfo service) {
        return service.getPorts().stream()
                      .filter(port -> port.getTags().contains("state"))
                      .map(PortInfo::getPort)
                      .findFirst();
    }

    private static long generationFromContainerState(JsonNode state) {
        return state.get("config").get("generation").asLong(-1);
    }

    private static StateApi createStateApi(Client client, URI uri) {
        WebTarget target = client.target(uri);
        return WebResourceFactory.newResource(StateApi.class, target);
    }

    private static class ServiceListResponse extends JSONResponse {

        // Pre-condition: servicesToCheck has a state port
        private ServiceListResponse(int status, Map<ServiceInfo, Long> servicesToCheck, URI uri, long wantedGeneration,
                                    long currentGeneration) {
            super(status);
            Cursor serviceArray = object.setArray("services");
            servicesToCheck.forEach((service, generation) -> {
                Cursor serviceObject = serviceArray.addObject();
                String hostName = service.getHostName();
                int statePort = getStatePort(service).get();
                serviceObject.setString("host", hostName);
                serviceObject.setLong("port", statePort);
                serviceObject.setString("type", service.getServiceType());
                serviceObject.setString("url", uri.toString() + "/" + hostName + ":" + statePort);
                serviceObject.setLong("currentGeneration", generation);
            });
            object.setString("url", uri.toString());
            object.setLong("currentGeneration", currentGeneration);
            object.setLong("wantedGeneration", wantedGeneration);
            object.setBool("converged", currentGeneration >= wantedGeneration);
        }
    }

    private static class ServiceResponse extends JSONResponse {

        private ServiceResponse(int status, URI uri, String hostname, Long wantedGeneration) {
            super(status);
            object.setString("url", uri.toString());
            object.setString("host", hostname);
            object.setLong("wantedGeneration", wantedGeneration);
        }

        static ServiceResponse createOkResponse(URI uri, String hostname, Long wantedGeneration, Long currentGeneration, boolean converged) {
            ServiceResponse serviceResponse = new ServiceResponse(200, uri, hostname, wantedGeneration);
            serviceResponse.object.setBool("converged", converged);
            serviceResponse.object.setLong("currentGeneration", currentGeneration);
            return serviceResponse;
        }

        static ServiceResponse createHostNotFoundInAppResponse(URI uri, String hostname, Long wantedGeneration) {
            ServiceResponse serviceResponse = new ServiceResponse(410, uri, hostname, wantedGeneration);
            serviceResponse.object.setString("problem", "Host:port (service) no longer part of application, refetch list of services.");
            return serviceResponse;
        }

        static ServiceResponse createErrorResponse(URI uri, String hostname, Long wantedGeneration, String error) {
            ServiceResponse serviceResponse = new ServiceResponse(500, uri, hostname, wantedGeneration);
            serviceResponse.object.setString("error", error);
            return serviceResponse;
        }

        static ServiceResponse createNotFoundResponse(URI uri, String hostname, Long wantedGeneration, String error) {
            ServiceResponse serviceResponse = new ServiceResponse(404, uri, hostname, wantedGeneration);
            serviceResponse.object.setString("error", error);
            return serviceResponse;
        }
    }

}
