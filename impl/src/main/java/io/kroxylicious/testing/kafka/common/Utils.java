/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.testing.kafka.common;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.Node;
import org.awaitility.Awaitility;
import org.hamcrest.Matchers;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static java.util.function.Predicate.not;
import static org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG;
import static org.slf4j.LoggerFactory.getLogger;

public class Utils {
    private static final Logger log = getLogger(Utils.class);

    public static void awaitExpectedBrokerCountInCluster(Map<String, Object> connectionConfig, int timeout, TimeUnit timeUnit, Integer expectedBrokerCount) {
        var knownReady = Collections.synchronizedSet(new HashSet<String>());
        var toProbe = Collections.synchronizedSet(new HashSet<String>());

        var originalBootstrap = String.valueOf(connectionConfig.get(BOOTSTRAP_SERVERS_CONFIG));
        toProbe.addAll(Arrays.asList(originalBootstrap.split(",")));

        while(knownReady.size() < expectedBrokerCount) {
            if (toProbe.isEmpty()) {
                throw new IllegalArgumentException(String.format("Ran out of addresses to probe before cluster ready: %s", originalBootstrap));
            }
            var probeAddress = toProbe.iterator().next();

            var copy = new HashMap<>(connectionConfig);
            copy.put(BOOTSTRAP_SERVERS_CONFIG, probeAddress);

            try (Admin admin = Admin.create(copy)) {
                Awaitility.await()
                        .pollDelay(Duration.ZERO)
                        .pollInterval(1, TimeUnit.SECONDS)
                        .atMost(timeout, timeUnit)
                        .ignoreExceptions()
                        .until(() -> {
                            log.info("describing cluster: {}", probeAddress);
                            try {
                                admin.describeCluster().controller().get().id();
                                var nodes = admin.describeCluster().nodes().get(10, TimeUnit.SECONDS);
                                log.info("got nodes: {}", nodes);

                                toProbe.addAll(nodes.stream().filter(not(Node::isEmpty))
                                                             .map(Utils::nodeToAddr)
                                                             .filter(not(knownReady::contains)).collect(Collectors.toSet()));
                                return nodes;
                            }
                            catch (InterruptedException | ExecutionException e) {
                                log.warn("caught: {}", e.getMessage(), e);
                            }
                            catch (TimeoutException te) {
                                log.warn("Kafka timed out describing the the cluster");
                            }
                            return Collections.emptyList();
                        }, Matchers.hasSize(expectedBrokerCount));
            }
            knownReady.add(probeAddress);
            toProbe.remove(probeAddress);
        }
    }

    private static String nodeToAddr(Node node) {
        return node.host() + ":" + node.port();
    }
}
