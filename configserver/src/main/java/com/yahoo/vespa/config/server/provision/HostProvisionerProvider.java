// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.provision;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.component.ComponentId;
import com.yahoo.component.provider.ComponentRegistry;
import com.yahoo.config.provision.Provisioner;
import java.util.logging.Level;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * This class is necessary to support both having and not having a host provisioner. We inject
 * a component registry here, which then enables us to check whether or not we have a provisioner available.
 * We only have a provisioner if we are running in hosted mode.
 *
 * @author Ulf Lilleengen
 */
public class HostProvisionerProvider {

    private final Optional<Provisioner> hostProvisioner;

    public HostProvisionerProvider(ComponentRegistry<Provisioner> hostProvisionerRegistry, ConfigserverConfig configserverConfig) {
        if (hostProvisionerRegistry.allComponents().isEmpty() || ! configserverConfig.hostedVespa()) {
            hostProvisioner = Optional.empty();
        } else {
            hostProvisioner = Optional.of(hostProvisionerRegistry.allComponents().get(0));
        }
    }

    private HostProvisionerProvider(ComponentRegistry<Provisioner> componentRegistry) {
        this(componentRegistry, new ConfigserverConfig(new ConfigserverConfig.Builder()));
    }

    /** Returns the host provisioner, or empty if we are not in hosted mode */
    public Optional<Provisioner> getHostProvisioner() {
        return hostProvisioner;
    }

    // for testing
    public static HostProvisionerProvider empty() {
        return new HostProvisionerProvider(new ComponentRegistry<>());
    }

    // for testing
    public static HostProvisionerProvider withProvisioner(Provisioner provisioner) {
        ComponentRegistry<Provisioner> registry = new ComponentRegistry<>();
        registry.register(ComponentId.createAnonymousComponentId("foobar"), provisioner);
        return new HostProvisionerProvider(registry, new ConfigserverConfig(new ConfigserverConfig.Builder().hostedVespa(true)));
    }

    /** Creates either an empty provider or a provider having the given provisioner */
    public static HostProvisionerProvider from(Optional<Provisioner> provisioner) {
        if (provisioner.isPresent())
            return withProvisioner(provisioner.get());
        else
            return empty();
    }

}
