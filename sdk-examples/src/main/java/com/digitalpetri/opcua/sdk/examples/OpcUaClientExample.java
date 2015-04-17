/*
 * Copyright 2015
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpetri.opcua.sdk.examples;

import java.security.Key;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.Timer.Context;
import com.digitalpetri.opcua.sdk.client.OpcUaClient;
import com.digitalpetri.opcua.sdk.client.OpcUaClientConfig;
import com.digitalpetri.opcua.stack.client.UaTcpStackClient;
import com.digitalpetri.opcua.stack.client.UaTcpStackClientConfig;
import com.digitalpetri.opcua.stack.core.Identifiers;
import com.digitalpetri.opcua.stack.core.security.SecurityPolicy;
import com.digitalpetri.opcua.stack.core.types.builtin.LocalizedText;
import com.digitalpetri.opcua.stack.core.types.builtin.NodeId;
import com.digitalpetri.opcua.stack.core.types.enumerated.TimestampsToReturn;
import com.digitalpetri.opcua.stack.core.types.structured.EndpointDescription;
import com.google.common.collect.Lists;

public class OpcUaClientExample {

    private static final MetricRegistry METRIC_REGISTRY = new MetricRegistry();
    static {
        ConsoleReporter reporter = ConsoleReporter.forRegistry(METRIC_REGISTRY)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();

        reporter.start(10, TimeUnit.SECONDS);
    }

    private static final int N_TIMES = 1000;
    private static final int N_CONCURRENT = 1000;
    private static final int N_NODES = 100;

    private static final Timer REQUEST_TIMER = METRIC_REGISTRY.timer("request-throughput");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: java -jar app.jar <endpoint url> <client count>");
            System.exit(-1);
        }

        String endpointUrl = args[0];
        int clientCount = Integer.parseInt(args[1]);

        List<OpcUaClient> clients = Lists.newArrayList();

        for (int i = 0; i < clientCount; i++) {
            clients.add(getOpcUaClient(endpointUrl));
        }

        for (int i = 0; i < clients.size(); i++) {
            read(clients.get(i), i, 0);
        }

        Thread.sleep(999999999);
    }

    private static void read(OpcUaClient client, int clientNumber, int count) {
        if (count == N_TIMES) {
            System.out.println("Client #" + clientNumber + " finished.");
            return;
        }

        List<NodeId> nodeIds = Collections.nCopies(N_NODES, Identifiers.Server_ServerStatus_CurrentTime);

        CompletableFuture<?>[] futures = new CompletableFuture<?>[N_CONCURRENT];

        for (int nc = 0; nc < N_CONCURRENT; nc++) {
            Context context = REQUEST_TIMER.time();
            futures[nc] = client.readValues(0.0d, TimestampsToReturn.Both, nodeIds);
            futures[nc].thenRun(context::stop);
        }

        CompletableFuture.allOf(futures).whenCompleteAsync((v, ex) -> {
            if (ex != null) {
                ex.printStackTrace();
            }

            read(client, clientNumber, count + 1);
        });
    }

    private static OpcUaClient getOpcUaClient(String endpointUrl) throws Exception {
        EndpointDescription[] endpoints = UaTcpStackClient.getEndpoints(endpointUrl).get();

        EndpointDescription endpoint = Arrays.stream(endpoints)
                .filter(e -> e.getSecurityPolicyUri().equals(SecurityPolicy.None.getSecurityPolicyUri()))
                .findFirst().orElseThrow(() -> new Exception("no desired endpoints returned"));

        System.out.println("Connecting to endpoint: " + endpoint.getEndpointUrl() + " [" + endpoint.getSecurityPolicyUri() + "]");

        KeyStoreLoader loader = new KeyStoreLoader().load();

        UaTcpStackClientConfig stackConfig = UaTcpStackClientConfig.builder()
                .setApplicationName(LocalizedText.english("SDK Example Client"))
                .setApplicationUri("urn:digitalpetri:sdk-example-client")
                .setCertificate(loader.getClientCertificate())
                .setKeyPair(loader.getClientKeyPair())
                .setEndpoint(endpoint)
                .build();

        UaTcpStackClient stackClient = new UaTcpStackClient(stackConfig);

        OpcUaClientConfig configConfig = OpcUaClientConfig.builder()
                .setStackClient(stackClient)
                .setRequestTimeout(120000)
                .build();

        OpcUaClient client = new OpcUaClient(configConfig);

        client.connect().get();

        return client;
    }

    private static class KeyStoreLoader {

        private static final String CLIENT_ALIAS = "sdk-example-client";
        private static final char[] PASSWORD = "test".toCharArray();

        private X509Certificate clientCertificate;
        private KeyPair clientKeyPair;

        public KeyStoreLoader load() throws Exception {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(getClass().getClassLoader().getResourceAsStream("example-keystore.pfx"), PASSWORD);

            Key clientPrivateKey = keyStore.getKey(CLIENT_ALIAS, PASSWORD);
            if (clientPrivateKey instanceof PrivateKey) {
                clientCertificate = (X509Certificate) keyStore.getCertificate(CLIENT_ALIAS);
                PublicKey clientPublicKey = clientCertificate.getPublicKey();
                clientKeyPair = new KeyPair(clientPublicKey, (PrivateKey) clientPrivateKey);
            }

            return this;
        }

        public X509Certificate getClientCertificate() {
            return clientCertificate;
        }

        public KeyPair getClientKeyPair() {
            return clientKeyPair;
        }

    }

}
