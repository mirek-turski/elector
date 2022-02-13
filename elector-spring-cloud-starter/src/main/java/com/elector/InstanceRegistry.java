package com.elector;

import static com.elector.Constant.ORDER_UNASSIGNED;
import static com.elector.Constant.STATE_ABSENT;
import static com.elector.Constant.STATE_DISCOVERED;
import static com.elector.Constant.STATE_INTRODUCED;
import static com.elector.Constant.STATE_SPARE;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.composite.CompositeDiscoveryClient;

/**
 * Manages instances of the service including discovery and resolution of order.
 */
public class InstanceRegistry {

  private static final Logger log = LoggerFactory.getLogger(InstanceRegistry.class);

  private final ElectorProperties properties;
  private final InstanceInfo selfInfo;
  // Key = instance id
  private final Map<String, InstanceInfo> peers = new ConcurrentHashMap<>();
  private final DiscoveryClient discoveryClient;

  public InstanceRegistry(
      ElectorProperties properties,
      InstanceInfo selfInfo,
      DiscoveryClient discoveryClient) {
    this.properties = properties;
    this.selfInfo = selfInfo;
    this.discoveryClient = discoveryClient;
  }

  public InstanceInfo getSelfInfo() {
    return selfInfo;
  }

  public Map<String, InstanceInfo> getPeers() {
    return this.peers;
  }

  public Map<String, InstanceInfo> discoverPeers() {
    final Map<String, InstanceInfo> discoveredPeers = new HashMap<>();
    if (log.isTraceEnabled()) {
      String clients;
      if (discoveryClient instanceof CompositeDiscoveryClient) {
        clients =
            ((CompositeDiscoveryClient) discoveryClient)
                .getDiscoveryClients().stream()
                .map(DiscoveryClient::description)
                .collect(Collectors.toList())
                .toString();
      } else {
        clients = discoveryClient.getClass().getSimpleName();
      }
      log.trace(
          "Getting all instances of {} service using {}",
          properties.getServiceName(),
          clients);
    }
    final List<ServiceInstance> instances =
        discoveryClient.getInstances(properties.getServiceName());
    instances.stream()
        .filter(serviceInstance -> !serviceInstance.getInstanceId().equals(selfInfo.getId()))
        .forEach(
            serviceInstance -> {
              final InstanceInfo info =
                  InstanceInfo.builder()
                      .id(serviceInstance.getInstanceId())
                      .weight(0)
                      .host(serviceInstance.getHost())
                      .order(ORDER_UNASSIGNED)
                      .state(STATE_DISCOVERED)
                      .last(Instant.now())
                      .build();
              discoveredPeers.put(info.getId(), info);
            });
    return discoveredPeers;
  }

  /**
   * Calculates correct order number for the requested instance based on the order of peers, states
   * and weights. The highest available order (the lowes number) will be returned from the pool. If
   * whole pool of numbers is already occupied, ORDER_UNASSIGNED will be given.
   *
   * @param candidate candidate to be checked.
   * @return Order number.
   */
  public int resolveOrder(@NotNull final InstanceInfo candidate) {
    if (candidate.inNeitherState(STATE_INTRODUCED, STATE_SPARE)) {
      return candidate.isActive() ? candidate.getOrder() : ORDER_UNASSIGNED;
    }
    final List<Integer> takenOrderNumbers =
        Stream.concat(peers.values().stream(), Stream.of(selfInfo))
            .filter(
                peer ->
                    peer.isActive()
                        || (peer.inState(STATE_ABSENT) && peer.getOrder() > ORDER_UNASSIGNED))
            .map(InstanceInfo::getOrder)
            .collect(Collectors.toList());
    final List<Integer> availableOrderNumbers =
        IntStream.range(1, properties.getPoolSize() + 1).boxed().collect(Collectors.toList());
    availableOrderNumbers.removeAll(takenOrderNumbers);
    if (availableOrderNumbers.isEmpty()) {
      return ORDER_UNASSIGNED;
    }
    final List<InstanceInfo> weightedUsurpers =
        Stream.concat(peers.values().stream(), Stream.of(selfInfo))
            .filter(peer -> peer.inEitherState(STATE_INTRODUCED, STATE_SPARE))
            .sorted(Comparator.comparingLong(InstanceInfo::getWeight).reversed())
            .collect(Collectors.toList());
    final int candidateIndex =
        IntStream.range(0, weightedUsurpers.size())
            .filter(index -> candidate.equals(weightedUsurpers.get(index)))
            .findFirst()
            .orElseThrow();
    int offset = candidateIndex - availableOrderNumbers.size() + 1;
    return offset > 0 ? ORDER_UNASSIGNED : availableOrderNumbers.get(candidateIndex);
  }

}
