/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.testing.kafka.common;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.slf4j.Logger;

import static java.util.function.Predicate.not;
import static org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * The type Utils.
 */
public class Utils {
    private static final Logger log = getLogger(Utils.class);
    private static final String CONSISTENCY_TEST = "__org_kroxylicious_testing_consistencyTest";

    private Utils() {
    }

    /**
     * Await expected broker count in cluster.
     * Verifies that all expected brokers are present in the cluster.
     * <p>
     * To Verify that all the expected brokers are in the cluster we create a topic with a replication factor = to the expected number of brokers.
     * We then poll describeTopics until
     *
     * @param connectionConfig the connection config
     * @param timeout the timeout
     * @param timeUnit the time unit
     * @param expectedBrokerCount the expected broker count
     */
    public static void awaitExpectedBrokerCountInClusterViaTopic(Map<String, Object> connectionConfig, int timeout, TimeUnit timeUnit, Integer expectedBrokerCount) {
        try (var admin = Admin.create(connectionConfig)) {
            log.debug("Creating topic: {} via {}", CONSISTENCY_TEST, connectionConfig.get(BOOTSTRAP_SERVERS_CONFIG));
            // Note we don't wait for the Topic to be created, we assume it will complete eventually.
            // As the thing we care about is it actually being replicated to the other brokers anyway so we wait to confirm replication.
            createTopic(expectedBrokerCount, admin);
            log.debug("Waiting for {} to be replicated to {} brokers", CONSISTENCY_TEST, expectedBrokerCount);
            awaitCondition(timeout, timeUnit)
                    .until(() -> {
                        log.debug("Calling describe topic");
                        final var promise = new CompletableFuture<Boolean>();
                        admin.describeTopics(Set.of(CONSISTENCY_TEST))
                                .allTopicNames()
                                .whenComplete((topicDescriptions, throwable) -> {
                                    if (throwable != null) {
                                        if (throwable instanceof CompletionException && throwable.getCause() instanceof UnknownTopicOrPartitionException) {
                                            log.debug("Cluster quorum test topic ({}) doesn't exist yet", CONSISTENCY_TEST);
                                        }
                                        else {
                                            log.warn("Unexpected failure describing topic: {} due to {}", CONSISTENCY_TEST, throwable.getMessage(), throwable);
                                        }
                                        promise.complete(false);
                                    }
                                    else {
                                        log.debug("Current topicDescriptions: {}", topicDescriptions);
                                        checkReplicaDistribution(expectedBrokerCount, promise, topicDescriptions);
                                    }
                                })
                                .get(1, TimeUnit.SECONDS);

                        final Boolean isQuorate = promise.getNow(false);
                        if (isQuorate) {
                            admin.deleteTopics(Set.of(CONSISTENCY_TEST));
                        }
                        return isQuorate;
                    });
        }
    }

    private static void checkReplicaDistribution(Integer expectedBrokerCount, CompletableFuture<Boolean> promise, Map<String, TopicDescription> topicDescriptions) {
        var topicDescription = topicDescriptions.get(CONSISTENCY_TEST);
        final long distinctReplicas = topicDescription.partitions()
                .stream()
                .map(TopicPartitionInfo::replicas)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        if (distinctReplicas == expectedBrokerCount) {
            promise.complete(true);
        }
        else {
            promise.complete(false);
        }
    }

    private static KafkaFuture<Void> createTopic(Integer expectedBrokerCount, Admin admin) {
        return admin.createTopics(Set.of(new NewTopic(CONSISTENCY_TEST, 1, expectedBrokerCount.shortValue())))
                .all()
                .whenComplete((unused, throwable) -> {
                    log.debug("Create topic future completed.");
                    if (throwable != null) {
                        log.warn("Failed to create topic: {} due to {}", CONSISTENCY_TEST, throwable.getMessage(), throwable);
                        if (throwable instanceof RetriableException) {
                            CompletableFuture.supplyAsync(() -> createTopic(expectedBrokerCount, admin));
                        }
                        else {
                            Assert.fail("Failed to create topic: " + CONSISTENCY_TEST + "  due to " + throwable.getMessage());
                        }
                    }
                });
    }

    /**
     * Await expected broker count in cluster.
     * Verifies that each broker in the cluster is returning the expected cluster size.
     *
     * @param connectionConfig the connection config
     * @param timeout the timeout
     * @param timeUnit the time unit
     * @param expectedBrokerCount the expected broker count
     */
    public static void awaitExpectedBrokerCountInCluster(Map<String, Object> connectionConfig, int timeout, TimeUnit timeUnit, Integer expectedBrokerCount) {
        var knownReady = Collections.synchronizedSet(new HashSet<String>());
        var toProbe = Collections.synchronizedSet(new HashSet<String>());

        var originalBootstrap = String.valueOf(connectionConfig.get(BOOTSTRAP_SERVERS_CONFIG));
        toProbe.addAll(Arrays.asList(originalBootstrap.split(",")));

        while (knownReady.size() < expectedBrokerCount && !toProbe.isEmpty()) {
            var probeAddress = toProbe.iterator().next();

            var copy = new HashMap<>(connectionConfig);
            copy.put(BOOTSTRAP_SERVERS_CONFIG, probeAddress);

            try (Admin admin = Admin.create(copy)) {
                awaitCondition(timeout, timeUnit)
                        .until(() -> {
                            log.debug("describing cluster using address: {}", probeAddress);
                            try {
                                admin.describeCluster().controller().get().id();
                                var nodes = admin.describeCluster().nodes().get(10, TimeUnit.SECONDS);
                                log.debug("{} sees peers: {}", probeAddress, nodes);

                                toProbe.addAll(
                                        nodes.stream()
                                                .filter(not(Node::isEmpty))
                                                .map(Utils::nodeToAddr)
                                                .filter(not(knownReady::contains))
                                                .collect(Collectors.toSet()));
                                log.debug("toProbe: {}, knownReady: {}", toProbe, knownReady);
                                return nodes;
                            }
                            catch (InterruptedException | ExecutionException e) {
                                log.warn("caught: {}", e.getMessage(), e);
                            }
                            catch (TimeoutException te) {
                                log.warn("Kafka timed out describing the the cluster");
                            }
                            return Collections.emptyList();
                        },
                                Matchers.hasSize(expectedBrokerCount));
            }
            knownReady.add(probeAddress);
            toProbe.remove(probeAddress);
        }

        int ready = knownReady.size();
        if (ready < expectedBrokerCount) {
            throw new IllegalArgumentException(String.format("Too few broker(s) became ready (%d), expected %d.", ready, expectedBrokerCount));
        }

    }

    public static ConditionFactory awaitCondition(int timeout, TimeUnit timeUnit) {
        return Awaitility.await()
                .pollDelay(Duration.ZERO)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .atMost(timeout, timeUnit)
                .ignoreExceptions();
    }

    private static String nodeToAddr(Node node) {
        return node.host() + ":" + node.port();
    }
}
