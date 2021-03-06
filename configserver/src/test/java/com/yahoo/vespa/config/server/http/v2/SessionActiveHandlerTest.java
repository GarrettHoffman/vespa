// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.http.v2;

import com.yahoo.component.Version;
import com.yahoo.config.application.api.ApplicationMetaData;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.TenantName;
import com.yahoo.config.provision.Zone;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.jdisc.http.HttpRequest;
import com.yahoo.slime.JsonFormat;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.config.server.TestComponentRegistry;
import com.yahoo.vespa.config.server.TimeoutBudget;
import com.yahoo.vespa.config.server.application.OrchestratorMock;
import com.yahoo.vespa.config.server.http.HandlerTest;
import com.yahoo.vespa.config.server.http.HttpErrorResponse;
import com.yahoo.vespa.config.server.http.SessionHandler;
import com.yahoo.vespa.config.server.http.SessionHandlerTest;
import com.yahoo.vespa.config.server.model.TestModelFactory;
import com.yahoo.vespa.config.server.modelfactory.ModelFactoryRegistry;
import com.yahoo.vespa.config.server.session.LocalSession;
import com.yahoo.vespa.config.server.session.PrepareParams;
import com.yahoo.vespa.config.server.tenant.Tenant;
import com.yahoo.vespa.config.server.tenant.TenantRepository;
import com.yahoo.vespa.curator.mock.MockCurator;
import com.yahoo.vespa.model.VespaModelFactory;
import org.hamcrest.core.Is;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static com.yahoo.jdisc.Response.Status.METHOD_NOT_ALLOWED;
import static com.yahoo.jdisc.Response.Status.NOT_FOUND;
import static com.yahoo.jdisc.Response.Status.OK;
import static com.yahoo.vespa.config.server.http.SessionHandlerTest.Cmd;
import static com.yahoo.vespa.config.server.http.SessionHandlerTest.createTestRequest;
import static com.yahoo.vespa.config.server.http.SessionHandlerTest.getRenderedString;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class SessionActiveHandlerTest {

    private static final File testApp = new File("src/test/apps/app");
    private static final String appName = "default";
    private static final TenantName tenantName = TenantName.from("activatetest");
    private static final String activatedMessage = " for tenant '" + tenantName + "' activated.";
    private static final String pathPrefix = "/application/v2/tenant/" + tenantName + "/session/";

    private SessionHandlerTest.MockProvisioner hostProvisioner;
    private TestComponentRegistry componentRegistry;
    private TenantRepository tenantRepository;
    private ApplicationRepository applicationRepository;
    private SessionActiveHandler handler;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void setup() {
        VespaModelFactory modelFactory = new TestModelFactory(Version.fromString("7.222.2"));
        hostProvisioner = new SessionHandlerTest.MockProvisioner();
        componentRegistry = new TestComponentRegistry.Builder()
                .curator(new MockCurator())
                .modelFactoryRegistry(new ModelFactoryRegistry(List.of((modelFactory))))
                .build();
        tenantRepository = new TenantRepository(componentRegistry, false);
        applicationRepository = new ApplicationRepository(tenantRepository, hostProvisioner,
                                                          new OrchestratorMock(), componentRegistry.getClock());
        tenantRepository.addTenant(tenantName);
        handler = createHandler();
    }

    @Test
    public void testActivation() throws Exception {
        activateAndAssertOK();
    }

    @Test
    public void testUnknownSession() {
        HttpResponse response = handler.handle(createTestRequest(pathPrefix, HttpRequest.Method.PUT, Cmd.ACTIVE, 9999L, "?timeout=1.0"));
        assertEquals(response.getStatus(), NOT_FOUND);
    }

    @Test
    public void require_that_handler_gives_error_for_unsupported_methods() throws Exception {
        testUnsupportedMethod(createTestRequest(pathPrefix, HttpRequest.Method.POST, Cmd.PREPARED, 1L));
        testUnsupportedMethod(createTestRequest(pathPrefix, HttpRequest.Method.DELETE, Cmd.PREPARED, 1L));
        testUnsupportedMethod(createTestRequest(pathPrefix, HttpRequest.Method.GET, Cmd.PREPARED, 1L));
    }

    private void testUnsupportedMethod(com.yahoo.container.jdisc.HttpRequest request) throws Exception {
        HttpResponse response = handler.handle(request);
        HandlerTest.assertHttpStatusCodeErrorCodeAndMessage(response,
                                                            METHOD_NOT_ALLOWED,
                                                            HttpErrorResponse.errorCodes.METHOD_NOT_ALLOWED,
                                                            "Method '" + request.getMethod().name() + "' is not supported");
    }

    protected class ActivateRequest {

        private long sessionId;
        private HttpResponse actResponse;
        private ApplicationMetaData metaData;
        private String subPath;

        ActivateRequest(String subPath) {
            this.subPath = subPath;
        }

        public SessionHandler getHandler() { return handler; }

        HttpResponse getActResponse() { return actResponse; }

        public long getSessionId() { return sessionId; }

        ApplicationMetaData getMetaData() { return metaData; }

        void invoke() {
            Tenant tenant = tenantRepository.getTenant(tenantName);
            long sessionId = applicationRepository.createSession(applicationId(),
                                                                 new TimeoutBudget(componentRegistry.getClock(), Duration.ofSeconds(10)),
                                                                 testApp);
            applicationRepository.prepare(tenant,
                                          sessionId,
                                          new PrepareParams.Builder().applicationId(applicationId()).build(),
                                          componentRegistry.getClock().instant());
            actResponse = handler.handle(createTestRequest(pathPrefix, HttpRequest.Method.PUT, Cmd.ACTIVE, sessionId, subPath));
            LocalSession session = applicationRepository.getActiveLocalSession(tenant, applicationId());
            metaData = session.getMetaData();
            this.sessionId = sessionId;
        }
    }

    private void activateAndAssertOK() throws Exception {
        ActivateRequest activateRequest = new ActivateRequest("");
        activateRequest.invoke();
        HttpResponse actResponse = activateRequest.getActResponse();
        String message = getRenderedString(actResponse);
        assertThat(message, actResponse.getStatus(), Is.is(OK));
        assertActivationMessageOK(activateRequest, message);
    }

    private void assertActivationMessageOK(ActivateRequest activateRequest, String message) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        new JsonFormat(true).encode(byteArrayOutputStream, activateRequest.getMetaData().getSlime());
        assertThat(message, containsString("\"tenant\":\"" + tenantName + "\",\"message\":\"Session " + activateRequest.getSessionId() + activatedMessage));
        assertThat(message, containsString("/application/v2/tenant/" + tenantName +
                "/application/" + appName +
                "/environment/" + "prod" +
                "/region/" + "default" +
                "/instance/" + "default"));
        assertTrue(hostProvisioner.activated);
        assertThat(hostProvisioner.lastHosts.size(), is(1));
    }

    private SessionActiveHandler createHandler() {
        return new SessionActiveHandler(SessionActiveHandler.testOnlyContext(),
                                        applicationRepository,
                                        Zone.defaultZone());
    }

    private ApplicationId applicationId() {
        return ApplicationId.from(tenantName.value(), appName, "default");
    }

}
