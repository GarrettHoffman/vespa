// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.zookeeper;

import com.google.inject.Inject;
import com.yahoo.cloud.config.ZookeeperServerConfig;
import com.yahoo.component.AbstractComponent;
import java.util.logging.Level;
import com.yahoo.security.KeyStoreBuilder;
import com.yahoo.security.KeyStoreType;
import com.yahoo.security.KeyStoreUtils;
import com.yahoo.security.KeyUtils;
import com.yahoo.security.SslContextBuilder;
import com.yahoo.security.X509CertificateUtils;
import com.yahoo.security.tls.TlsContext;
import com.yahoo.security.tls.TransportSecurityOptions;
import com.yahoo.security.tls.TransportSecurityUtils;
import com.yahoo.text.Utf8;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.yahoo.vespa.defaults.Defaults.getDefaults;

/**
 * Writes zookeeper config and starts zookeeper server.
 *
 * @author Ulf Lilleengen
 * @author Harald Musum
 */
public class VespaZooKeeperServerImpl extends AbstractComponent implements Runnable, VespaZooKeeperServer {

    private static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(VespaZooKeeperServerImpl.class.getName());
    private static final String ZOOKEEPER_JMX_LOG4J_DISABLE = "zookeeper.jmx.log4j.disable";
    static final String ZOOKEEPER_JUTE_MAX_BUFFER = "jute.maxbuffer";
    private final Thread zkServerThread;
    private final ZookeeperServerConfig zookeeperServerConfig;
    private final String configFilePath;
    private final String jksKeyStoreFilePath;

    VespaZooKeeperServerImpl(ZookeeperServerConfig zookeeperServerConfig, boolean startServer,
                            Optional<TransportSecurityOptions> transportSecurityOptions) {
        this.zookeeperServerConfig = zookeeperServerConfig;
        System.setProperty("zookeeper.jmx.log4j.disable", "true");
        System.setProperty("zookeeper.snapshot.trust.empty", Boolean.valueOf(zookeeperServerConfig.trustEmptySnapshot()).toString());
        System.setProperty(ZOOKEEPER_JUTE_MAX_BUFFER, Integer.valueOf(zookeeperServerConfig.juteMaxBuffer()).toString());

        configFilePath = getDefaults().underVespaHome(zookeeperServerConfig.zooKeeperConfigFile());
        jksKeyStoreFilePath = getDefaults().underVespaHome(zookeeperServerConfig.jksKeyStoreFile());
        writeConfigToDisk(zookeeperServerConfig, transportSecurityOptions);
        zkServerThread = new Thread(this, "zookeeper server");
        if (startServer) {
            zkServerThread.start();
        }
    }

    @Inject
    public VespaZooKeeperServerImpl(ZookeeperServerConfig zookeeperServerConfig) {
        this(zookeeperServerConfig, true, TransportSecurityUtils.getOptions());
    }

    private void writeConfigToDisk(ZookeeperServerConfig config, Optional<TransportSecurityOptions> transportSecurityOptions) {
        new File(configFilePath).getParentFile().mkdirs();

        try {
            writeZooKeeperConfigFile(zookeeperServerConfig, transportSecurityOptions);
            writeMyIdFile(config);
            transportSecurityOptions.ifPresent(this::writeJksKeystore);
        } catch (IOException e) {
            throw new RuntimeException("Error writing zookeeper config", e);
        }
   }

    private void writeZooKeeperConfigFile(ZookeeperServerConfig config,
                                          Optional<TransportSecurityOptions> transportSecurityOptions) throws IOException {
        try (FileWriter writer = new FileWriter(configFilePath)) {
            writer.write(transformConfigToString(config, transportSecurityOptions));
        }
    }

    private String transformConfigToString(ZookeeperServerConfig config,
                                           Optional<TransportSecurityOptions> transportSecurityOptions) {
        StringBuilder sb = new StringBuilder();
        sb.append("tickTime=").append(config.tickTime()).append("\n");
        sb.append("initLimit=").append(config.initLimit()).append("\n");
        sb.append("syncLimit=").append(config.syncLimit()).append("\n");
        sb.append("maxClientCnxns=").append(config.maxClientConnections()).append("\n");
        sb.append("snapCount=").append(config.snapshotCount()).append("\n");
        sb.append("dataDir=").append(getDefaults().underVespaHome(config.dataDir())).append("\n");
        sb.append("clientPort=").append(config.clientPort()).append("\n");
        sb.append("secureClientPort=").append(config.secureClientPort()).append("\n");
        sb.append("autopurge.purgeInterval=").append(config.autopurge().purgeInterval()).append("\n");
        sb.append("autopurge.snapRetainCount=").append(config.autopurge().snapRetainCount()).append("\n");
        // See http://zookeeper.apache.org/doc/r3.5.5/zookeeperAdmin.html#sc_zkCommands
        // Includes all available commands in 3.5, except 'wchc' and 'wchp'
        sb.append("4lw.commands.whitelist=conf,cons,crst,dirs,dump,envi,mntr,ruok,srst,srvr,stat,wchs").append("\n");
        sb.append("admin.enableServer=false").append("\n");
        // Need NettyServerCnxnFactory to be able to use TLS for communication
        sb.append("serverCnxnFactory=org.apache.zookeeper.server.NettyServerCnxnFactory").append("\n");
        ensureThisServerIsRepresented(config.myid(), config.server());
        config.server().forEach(server -> addServerToCfg(sb, server));
        SSLContext sslContext = new SslContextBuilder().build();
        sb.append(new TlsQuorumConfig(sslContext, jksKeyStoreFilePath).createConfig(config, transportSecurityOptions));
        sb.append(new TlsClientServerConfig(sslContext, jksKeyStoreFilePath).createConfig(config, transportSecurityOptions));
        return sb.toString();
    }

    private void writeMyIdFile(ZookeeperServerConfig config) throws IOException {
        if (config.server().size() > 1) {
            try (FileWriter writer = new FileWriter(getDefaults().underVespaHome(config.myidFile()))) {
                writer.write(config.myid() + "\n");
            }
        }
    }

    private void writeJksKeystore(TransportSecurityOptions options) {
        Path privateKeyFile = options.getPrivateKeyFile().orElseThrow(() -> new RuntimeException("Could not find private key file"));
        Path certificatesFile = options.getCertificatesFile().orElseThrow(() -> new RuntimeException("Could not find certificates file"));

        PrivateKey privateKey;
        List<X509Certificate> certificates;
        try {
            privateKey = KeyUtils.fromPemEncodedPrivateKey(Utf8.toString(Files.readAllBytes(privateKeyFile)));
            certificates = X509CertificateUtils.certificateListFromPem(Utf8.toString(Files.readAllBytes(certificatesFile)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        KeyStoreBuilder keyStoreBuilder = KeyStoreBuilder
                .withType(KeyStoreType.JKS)
                .withKeyEntry("foo", privateKey, certificates);

        KeyStoreUtils.writeKeyStoreToFile(keyStoreBuilder.build(), Paths.get(jksKeyStoreFilePath));
    }

    private void ensureThisServerIsRepresented(int myid, List<ZookeeperServerConfig.Server> servers) {
        boolean found = false;
        for (ZookeeperServerConfig.Server server : servers) {
            if (myid == server.id()) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new RuntimeException("No id in zookeeper server list that corresponds to my id(" + myid + ")");
        }
    }

    private void addServerToCfg(StringBuilder sb, ZookeeperServerConfig.Server server) {
        sb.append("server.").append(server.id()).append("=").append(server.hostname()).append(":").append(server.quorumPort()).append(":").append(server.electionPort()).append("\n");
    }

    private void shutdown() {
        zkServerThread.interrupt();
        try {
            zkServerThread.join();
        } catch (InterruptedException e) {
            log.log(LogLevel.WARNING, "Error joining server thread on shutdown", e);
        }
    }

    @Override
    public void run() {
        System.setProperty(ZOOKEEPER_JMX_LOG4J_DISABLE, "true");
        String[] args = new String[]{getDefaults().underVespaHome(zookeeperServerConfig.zooKeeperConfigFile())};
        log.log(LogLevel.INFO, "Starting ZooKeeper server with config file " + args[0] +
                ". Trying to establish ZooKeeper quorum (members: " + zookeeperServerHostnames(zookeeperServerConfig) + ")");
        org.apache.zookeeper.server.quorum.QuorumPeerMain.main(args);
    }

    @Override
    public void deconstruct() {
        shutdown();
        super.deconstruct();
    }

    private static Set<String> zookeeperServerHostnames(ZookeeperServerConfig zookeeperServerConfig) {
        return zookeeperServerConfig.server().stream().map(ZookeeperServerConfig.Server::hostname).collect(Collectors.toSet());
    }

    private interface TlsConfig {
        default Set<String> allowedCiphers(SSLContext sslContext) { return new TreeSet<>(TlsContext.getAllowedCipherSuites(sslContext)); }

        default Set<String> allowedProtocols(SSLContext sslContext) { return new TreeSet<>(TlsContext.getAllowedProtocols(sslContext)); }

        default Optional<String> getEnvironmentVariable(String variableName) {
            return Optional.ofNullable(System.getenv().get(variableName))
                    .filter(var -> !var.isEmpty());
        }

        default void validateOptions(Optional<TransportSecurityOptions> transportSecurityOptions, String tlsSetting) {
            if (transportSecurityOptions.isEmpty() && !tlsSetting.equals("OFF"))
                throw new RuntimeException("Could not retrieve transport security options");
        }

        String configFieldPrefix();

        String jksKeyStoreFilePath();

        SSLContext sslContext();

        default String createCommonKeyStoreTrustStoreOptions(Optional<TransportSecurityOptions> transportSecurityOptions) {
            StringBuilder sb = new StringBuilder();
            transportSecurityOptions.ifPresent(options -> {
                sb.append(configFieldPrefix()).append(".keyStore.location=").append(jksKeyStoreFilePath()).append("\n");
                sb.append(configFieldPrefix()).append(".keyStore.type=JKS\n");

                Path caCertificatesFile = options.getCaCertificatesFile().orElseThrow(() -> new RuntimeException("Could not find ca certificates file"));
                sb.append(configFieldPrefix()).append(".trustStore.location=").append(caCertificatesFile).append("\n");
                sb.append(configFieldPrefix()).append(".trustStore.type=PEM\n");
            });
            return sb.toString();
        }

        default String createCommonConfig() {
            StringBuilder sb = new StringBuilder();
            sb.append(configFieldPrefix()).append(".hostnameVerification=false\n");
            sb.append(configFieldPrefix()).append(".clientAuth=NEED\n");
            sb.append(configFieldPrefix()).append(".ciphersuites=").append(String.join(",", allowedCiphers(sslContext()))).append("\n");
            sb.append(configFieldPrefix()).append(".enabledProtocols=").append(String.join(",", allowedProtocols(sslContext()))).append("\n");
            sb.append(configFieldPrefix()).append(".protocol=").append(sslContext().getProtocol()).append("\n");

            return sb.toString();
        }

    }

    static class TlsClientServerConfig implements TlsConfig {

        private final SSLContext sslContext;
        private final String jksKeyStoreFilePath;

        TlsClientServerConfig(SSLContext sslContext, String jksKeyStoreFilePath) {
            this.sslContext = sslContext;
            this.jksKeyStoreFilePath = jksKeyStoreFilePath;
        }

        String createConfig(ZookeeperServerConfig config, Optional<TransportSecurityOptions> transportSecurityOptions) {
            String tlsSetting = getEnvironmentVariable("VESPA_TLS_FOR_ZOOKEEPER_CLIENT_SERVER_COMMUNICATION")
                    .orElse(config.tlsForClientServerCommunication().name());
            validateOptions(transportSecurityOptions, tlsSetting);

            StringBuilder sb = new StringBuilder(createCommonConfig());
            boolean portUnification;
            switch (tlsSetting) {
                case "OFF":
                case "TLS_ONLY":
                    portUnification = false;
                    break;
                case "PORT_UNIFICATION":
                case "TLS_WITH_PORT_UNIFICATION":
                    portUnification = true;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown value of config setting tlsForClientServerCommunication: " + tlsSetting);
            }
            sb.append("client.portUnification=").append(portUnification).append("\n");
            sb.append(createCommonKeyStoreTrustStoreOptions(transportSecurityOptions));

            return sb.toString();
        }

        @Override
        public String configFieldPrefix() {
            return "ssl";
        }

        @Override
        public String jksKeyStoreFilePath() {
            return jksKeyStoreFilePath;
        }

        @Override
        public SSLContext sslContext() {
            return sslContext;
        }
    }

    static class TlsQuorumConfig implements TlsConfig {

        private final SSLContext sslContext;
        private final String jksKeyStoreFilePath;

        TlsQuorumConfig(SSLContext sslContext, String jksKeyStoreFilePath) {
            this.sslContext = sslContext;
            this.jksKeyStoreFilePath = jksKeyStoreFilePath;
        }

        String createConfig(ZookeeperServerConfig config, Optional<TransportSecurityOptions> transportSecurityOptions) {
            String tlsSetting = getEnvironmentVariable("VESPA_TLS_FOR_ZOOKEEPER_QUORUM_COMMUNICATION")
                    .orElse(config.tlsForQuorumCommunication().name());
            validateOptions(transportSecurityOptions, tlsSetting);

            StringBuilder sb = new StringBuilder(createCommonConfig());
            boolean sslQuorum;
            boolean portUnification;
            switch (tlsSetting) {
                case "OFF":
                    sslQuorum = false;
                    portUnification = false;
                    break;
                case "PORT_UNIFICATION":
                    sslQuorum = false;
                    portUnification = true;
                    break;
                case "TLS_WITH_PORT_UNIFICATION":
                    sslQuorum = true;
                    portUnification = true;
                    break;
                case "TLS_ONLY":
                    sslQuorum = true;
                    portUnification = false;
                    break;
                default: throw new IllegalArgumentException("Unknown value of config setting tlsForQuorumCommunication: " + tlsSetting);
            }
            sb.append("sslQuorum=").append(sslQuorum).append("\n");
            sb.append("portUnification=").append(portUnification).append("\n");
            sb.append(createCommonKeyStoreTrustStoreOptions(transportSecurityOptions));

            return sb.toString();
        }

        @Override
        public String configFieldPrefix() {
            return "ssl.quorum";
        }

        @Override
        public String jksKeyStoreFilePath() {
            return jksKeyStoreFilePath;
        }

        @Override
        public SSLContext sslContext() {
            return sslContext;
        }

    }

}
