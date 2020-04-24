// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document.restapi.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yahoo.cloud.config.ClusterListConfig;
import com.yahoo.container.handler.ThreadpoolConfig;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.container.jdisc.LoggingRequestHandler;
import com.yahoo.container.logging.AccessLog;
import com.yahoo.document.DocumentTypeManager;
import com.yahoo.document.TestAndSetCondition;
import com.yahoo.document.config.DocumentmanagerConfig;

import com.yahoo.document.json.SingleDocumentParser;
import com.yahoo.document.restapi.OperationHandler;
import com.yahoo.document.restapi.OperationHandlerImpl;
import com.yahoo.document.restapi.Response;
import com.yahoo.document.restapi.RestApiException;
import com.yahoo.document.restapi.RestUri;
import com.yahoo.document.select.DocumentSelector;
import com.yahoo.document.select.parser.ParseException;
import com.yahoo.documentapi.messagebus.MessageBusDocumentAccess;
import com.yahoo.documentapi.messagebus.MessageBusParams;
import com.yahoo.documentapi.messagebus.loadtypes.LoadTypeSet;
import java.util.logging.Level;
import com.yahoo.metrics.simple.MetricReceiver;
import com.yahoo.text.Text;
import com.yahoo.vespa.config.content.LoadTypeConfig;
import com.yahoo.vespa.config.content.AllClustersBucketSpacesConfig;
import com.yahoo.vespaclient.ClusterDef;
import com.yahoo.vespaclient.ClusterList;
import com.yahoo.vespaxmlparser.DocumentFeedOperation;
import com.yahoo.vespaxmlparser.FeedOperation;
import com.yahoo.yolean.Exceptions;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static com.yahoo.jdisc.Response.Status.BAD_REQUEST;

/**
 * API for handling single operation on a document and visiting.
 *
 * @author Haakon Dybdahl
 */
public class RestApi extends LoggingRequestHandler {

    private static final String CREATE_PARAMETER_NAME = "create";
    private static final String CONDITION_PARAMETER_NAME = "condition";
    private static final String ROUTE_PARAMETER_NAME = "route";
    private static final String DOCUMENTS = "documents";
    private static final String FIELDS = "fields";
    private static final String DOC_ID_NAME = "id";
    private static final String PATH_NAME = "pathId";
    private static final String SELECTION = "selection";
    private static final String CLUSTER = "cluster";
    private static final String CONTINUATION = "continuation";
    private static final String WANTED_DOCUMENT_COUNT = "wantedDocumentCount";
    private static final String FIELD_SET = "fieldSet";
    private static final String CONCURRENCY = "concurrency";
    private static final String BUCKET_SPACE = "bucketSpace";
    private static final String APPLICATION_JSON = "application/json";
    private final OperationHandler operationHandler;
    private SingleDocumentParser singleDocumentParser;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger threadsAvailableForApi;

    @Inject
    public RestApi(LoggingRequestHandler.Context parentCtx, DocumentmanagerConfig documentManagerConfig,
                   LoadTypeConfig loadTypeConfig, ThreadpoolConfig threadpoolConfig,
                   AllClustersBucketSpacesConfig bucketSpacesConfig,
                   ClusterListConfig clusterListConfig, MetricReceiver metricReceiver) {
        super(parentCtx);
        MessageBusParams params = new MessageBusParams(new LoadTypeSet(loadTypeConfig));
        params.setDocumentmanagerConfig(documentManagerConfig);
        this.operationHandler = new OperationHandlerImpl(new MessageBusDocumentAccess(params),
                                                        fixedClusterEnumeratorFromConfig(clusterListConfig),
                                                        fixedBucketSpaceResolverFromConfig(bucketSpacesConfig),
                                                        metricReceiver);
        this.singleDocumentParser = new SingleDocumentParser(new DocumentTypeManager(documentManagerConfig));
        // 40% of the threads can be blocked before we deny requests.
        if (threadpoolConfig != null) {
            threadsAvailableForApi = new AtomicInteger(Math.max((int) (0.4 * threadpoolConfig.maxthreads()), 1));
        } else {
            log.warning("No config for threadpool, using 200 for max blocking threads for document rest API.");
            threadsAvailableForApi = new AtomicInteger(200);
        }
    }

    // For testing and development
    public RestApi(Executor executor,
                   AccessLog accessLog,
                   OperationHandler operationHandler,
                   int threadsAvailable) {
        super(executor, accessLog, null);
        this.operationHandler = operationHandler;
        this.threadsAvailableForApi = new AtomicInteger(threadsAvailable);
    }

    @Override
    public void destroy() {
        operationHandler.shutdown();
    }

    // For testing and development
    protected void setDocTypeManagerForTests(DocumentTypeManager docTypeManager) {
        this.singleDocumentParser = new SingleDocumentParser(docTypeManager);
    }

    private static OperationHandlerImpl.ClusterEnumerator fixedClusterEnumeratorFromConfig(ClusterListConfig config) {
        List<ClusterDef> clusters = Collections.unmodifiableList(new ClusterList(config).getStorageClusters());
        return () -> clusters;
    }

    private static OperationHandlerImpl.BucketSpaceResolver fixedBucketSpaceResolverFromConfig(AllClustersBucketSpacesConfig bucketSpacesConfig) {
        return (clusterId, docType) ->
            Optional.ofNullable(bucketSpacesConfig.cluster(clusterId))
                    .map(cluster -> cluster.documentType(docType))
                    .map(type -> type.bucketSpace());
    }

    private static Optional<String> requestProperty(String parameter, HttpRequest request) {
        String property = request.getProperty(parameter);
        if (property != null && ! property.isEmpty()) {
            return Optional.of(property);
        }
        return Optional.empty();
    }

    private static boolean parseBooleanStrict(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        } else if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(String.format("Value not convertible to bool: '%s'", value));
    }

    private static Optional<Boolean> parseBoolean(String parameter, HttpRequest request) {
        try {
            Optional<String> property = requestProperty(parameter, request);
            return property.map(RestApi::parseBooleanStrict);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid value for '" + parameter + "' parameter: " +
                                               "Must be empty, true, or false but was '" +
                                               request.getProperty(parameter) + "'");
        }
    }

    private static int parsePositiveInt(String str) throws NumberFormatException {
        int parsed = Integer.parseInt(str);
        if (parsed <= 0) {
            throw new IllegalArgumentException("Parsed number was negative or zero");
        }
        return parsed;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        try {
            if (threadsAvailableForApi.decrementAndGet() < 1) {
                return Response.createErrorResponse(429 /* Too Many Requests */,
                                                    "Too many parallel requests, consider using http-vespa-java-client. Please try again later.",
                                                    RestUri.apiErrorCodes.TOO_MANY_PARALLEL_REQUESTS);
            }
            return handleInternal(request);
        } finally {
            threadsAvailableForApi.incrementAndGet();
        }
    }

    private static void validateUriStructureForRequestMethod(RestUri uri, com.yahoo.jdisc.http.HttpRequest.Method method)
            throws RestApiException {
        if ((method != com.yahoo.jdisc.http.HttpRequest.Method.GET) && uri.isRootOnly()) {
            throw new RestApiException(Response.createErrorResponse(BAD_REQUEST,
                                       "Root /document/v1/ requests only supported for HTTP GET",
                                       RestUri.apiErrorCodes.ERROR_ID_BASIC_USAGE));
        }
    }

    private static boolean isVisitRequestUri(RestUri uri) {
        return (uri.isRootOnly() || uri.getDocId().isEmpty());
    }

    // protected for testing
    protected HttpResponse handleInternal(HttpRequest request) {
        RestUri restUri = null;
        try {
            restUri = new RestUri(request.getUri());
            validateUriStructureForRequestMethod(restUri, request.getMethod());

            Optional<Boolean> create;
            try {
                create = parseBoolean(CREATE_PARAMETER_NAME, request);
            }
            catch (IllegalArgumentException e) {
                return Response.createErrorResponse(400, e.getMessage(), RestUri.apiErrorCodes.INVALID_CREATE_VALUE);
            }

            String condition = request.getProperty(CONDITION_PARAMETER_NAME);
            Optional<String> route = Optional.ofNullable(nonEmpty(request.getProperty(ROUTE_PARAMETER_NAME), ROUTE_PARAMETER_NAME));

            Optional<ObjectNode> resultJson = Optional.empty();
            switch (request.getMethod()) {
                case GET:    // Vespa Visit/Get
                    return isVisitRequestUri(restUri) ? handleVisit(restUri, request) : handleGet(restUri, request);
                case POST:   // Vespa Put
                    operationHandler.put(restUri, createPutOperation(request, restUri.generateFullId(), condition), route);
                    break;
                case PUT:    // Vespa Update
                    operationHandler.update(restUri, createUpdateOperation(request, restUri.generateFullId(), condition, create), route);
                    break;
                case DELETE: // Vespa Delete
                    operationHandler.delete(restUri, condition, route);
                    break;
                default:
                    return new Response(405, Optional.empty(), Optional.of(restUri));
            }
            return new Response(200, resultJson, Optional.of(restUri));
        }
        catch (RestApiException e) {
            return e.getResponse();
        }
        catch (IllegalArgumentException userException) {
            return Response.createErrorResponse(400, Exceptions.toMessageString(userException),
                                                restUri,
                                                RestUri.apiErrorCodes.PARSER_ERROR);
        }
        catch (RuntimeException systemException) {
            log.log(LogLevel.WARNING, "Internal runtime exception during Document V1 request handling", systemException);
            return Response.createErrorResponse(500, Exceptions.toMessageString(systemException),
                                                restUri,
                                                RestUri.apiErrorCodes.UNSPECIFIED);
        }
    }

    private FeedOperation createPutOperation(HttpRequest request, String id, String condition) {
        FeedOperation put = singleDocumentParser.parsePut(request.getData(), id);
        if (condition != null && ! condition.isEmpty()) {
            return new DocumentFeedOperation(put.getDocument(), new TestAndSetCondition(condition));
        }
        return put;
    }

    private FeedOperation createUpdateOperation(HttpRequest request, String id, String condition, Optional<Boolean> create) {
        FeedOperation update = singleDocumentParser.parseUpdate(request.getData(), id);
        if (condition != null && ! condition.isEmpty()) {
            update.getDocumentUpdate().setCondition(new TestAndSetCondition(condition));
        }
        create.ifPresent(c -> update.getDocumentUpdate().setCreateIfNonExistent(c));
        return update;
    }

    private HttpResponse handleGet(RestUri restUri, HttpRequest request) throws RestApiException {
        final Optional<String> fieldSet = requestProperty(FIELD_SET, request);
        final Optional<String> cluster  = requestProperty(CLUSTER, request);
        final Optional<String> getDocument = operationHandler.get(restUri, fieldSet, cluster);
        final ObjectNode resultNode = mapper.createObjectNode();
        if (getDocument.isPresent()) {
            final JsonNode parseNode;
            try {
                parseNode = mapper.readTree(getDocument.get());
            } catch (IOException e) {
                throw new RuntimeException("Failed while parsing my own results", e);
            }
            resultNode.putPOJO(FIELDS, parseNode.get(FIELDS));
        }
        resultNode.put(DOC_ID_NAME, restUri.generateFullId());
        resultNode.put(PATH_NAME, restUri.getRawPath());

        return new HttpResponse(getDocument.isPresent() ? 200 : 404) {
            @Override
            public String getContentType() { return APPLICATION_JSON; }
            @Override
            public void render(OutputStream outputStream) throws IOException {
                outputStream.write(resultNode.toString().getBytes(StandardCharsets.UTF_8.name()));
            }
        };
    }

    private static HttpResponse createInvalidParameterResponse(String parameter, String explanation) {
        return Response.createErrorResponse(400, String.format("Invalid '%s' value. %s", parameter, explanation), RestUri.apiErrorCodes.UNSPECIFIED);
    }

    static class BadRequestParameterException extends IllegalArgumentException {
        private String parameter;

        BadRequestParameterException(String parameter, String message) {
            super(message);
            this.parameter = parameter;
        }

        String getParameter() {
            return parameter;
        }
    }

    private static Optional<Integer> parsePositiveIntegerRequestParameter(String parameter, HttpRequest request) {
        Optional<String> property = requestProperty(parameter, request);
        if (!property.isPresent()) {
            return Optional.empty();
        }
        try {
            return property.map(RestApi::parsePositiveInt);
        } catch (IllegalArgumentException e) {
            throw new BadRequestParameterException(parameter, "Expected positive integer");
        }
    }

    private static OperationHandler.VisitOptions visitOptionsFromRequest(HttpRequest request) {
        final OperationHandler.VisitOptions.Builder optionsBuilder = OperationHandler.VisitOptions.builder();

        Optional.ofNullable(request.getProperty(CLUSTER)).ifPresent(c -> optionsBuilder.cluster(c));
        Optional.ofNullable(request.getProperty(CONTINUATION)).ifPresent(c -> optionsBuilder.continuation(c));
        Optional.ofNullable(request.getProperty(FIELD_SET)).ifPresent(fs -> optionsBuilder.fieldSet(fs));
        Optional.ofNullable(request.getProperty(BUCKET_SPACE)).ifPresent(s -> optionsBuilder.bucketSpace(s));
        parsePositiveIntegerRequestParameter(WANTED_DOCUMENT_COUNT, request).ifPresent(c -> optionsBuilder.wantedDocumentCount(c));
        parsePositiveIntegerRequestParameter(CONCURRENCY, request).ifPresent(c -> optionsBuilder.concurrency(c));

        return optionsBuilder.build();
    }

    /**
     * Escapes all single quotes in input string.
     * @param original non-escaped string that may contain single quotes
     * @return original if no quotes to escaped were found, otherwise a quote-escaped string
     */
    private static String singleQuoteEscapedString(String original) {
        if (original.indexOf('\'') == -1) {
            return original;
        }
        StringBuilder builder = new StringBuilder(original.length() + 1);
        for (int i = 0; i < original.length(); ++i) {
            char c = original.charAt(i);
            if (c != '\'') {
                builder.append(c);
            } else {
                builder.append("\\'");
            }
        }
        return builder.toString();
    }


    private String nonEmpty(String value, String name) {
        if (value != null && value.isEmpty())
            throw new IllegalArgumentException("'" + name + "' cannot be empty");
        return value;
    }

    private static long parseAndValidateVisitNumericId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BadRequestParameterException(SELECTION, "Failed to parse numeric part of selection URI");
        }
    }

    private static String validateAndBuildLocationSubExpression(RestUri.Group group) {
        if (group.name == 'n') {
            return String.format("id.user==%d", parseAndValidateVisitNumericId(group.value));
        } else {
            // Cannot feed documents with groups that don't pass this test, so it makes sense
            // to enforce this symmetry when trying to retrieve them as well.
            Text.validateTextString(group.value).ifPresent(codepoint -> {
                throw new BadRequestParameterException(SELECTION, String.format(
                        "Failed to parse group part of selection URI; contains invalid text code point U%04X", codepoint));
            });
            return String.format("id.group=='%s'", singleQuoteEscapedString(group.value));
        }
    }

    private static void validateDocumentSelectionSyntax(String expression) {
        try {
            new DocumentSelector(expression);
        } catch (ParseException e) {
            throw new BadRequestParameterException(SELECTION, String.format("Failed to parse expression given in 'selection'" +
                    " parameter. Must be a complete and valid sub-expression. Error: %s", e.getMessage()));
        }
    }

    private static String documentSelectionFromRequest(RestUri restUri, HttpRequest request) throws BadRequestParameterException {
        String documentSelection = Optional.ofNullable(request.getProperty(SELECTION)).orElse("");
        if (!documentSelection.isEmpty()) {
            // Ensure that the selection parameter sub-expression is complete and valid by itself.
            validateDocumentSelectionSyntax(documentSelection);
        }
        if (restUri.getGroup().isPresent() && ! restUri.getGroup().get().value.isEmpty()) {
            String locationSubExpression = validateAndBuildLocationSubExpression(restUri.getGroup().get());
            if (documentSelection.isEmpty()) {
                documentSelection = locationSubExpression;
            } else {
                documentSelection = String.format("%s and (%s)", locationSubExpression, documentSelection);
            }
        }
        return documentSelection;
    }

    private HttpResponse handleVisit(RestUri restUri, HttpRequest request) throws RestApiException {
        String documentSelection;
        OperationHandler.VisitOptions options;
        try {
            documentSelection = documentSelectionFromRequest(restUri, request);
            options = visitOptionsFromRequest(request);
        } catch (BadRequestParameterException e) {
            return createInvalidParameterResponse(e.getParameter(), e.getMessage());
        }
        OperationHandler.VisitResult visit = operationHandler.visit(restUri, documentSelection, options);
        ObjectNode resultNode = mapper.createObjectNode();
        visit.token.ifPresent(t -> resultNode.put(CONTINUATION, t));
        resultNode.putArray(DOCUMENTS).addPOJO(visit.documentsAsJsonList);
        resultNode.put(PATH_NAME, restUri.getRawPath());

        HttpResponse httpResponse = new HttpResponse(200) {
            @Override
            public String getContentType() { return APPLICATION_JSON; }
            @Override
            public void render(OutputStream outputStream) throws IOException {
                try {
                    outputStream.write(resultNode.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        return httpResponse;
    }
}
