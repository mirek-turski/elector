package com.selfrule;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.factory.Mappers;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.handler.GenericHandler;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Controller;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.selfrule.InstanceConstant.*;

/**
 * Manages instances of the service. The instances will be ordered starting from 0. Every new
 * instance will negotiate the highest available order number.
 */
@Slf4j
@EnableScheduling
@RequiredArgsConstructor
@Controller
public class InstanceController
    implements GenericHandler<InstanceEvent>, InfoContributor, SchedulingConfigurer {

  private final InstanceConfig config;
  private final InstanceInfo selfInfo;
  private final DiscoveryClient discoveryClient;
  private final IntegrationFlow outUdpAdapter;
  private final ApplicationEventPublisher eventPublisher;
  private final InstanceMapper mapper = Mappers.getMapper(InstanceMapper.class);
  // Key = voter id
  private final Map<String, InstanceEvent> ballots = new ConcurrentHashMap<>();
  // Key = pod id
  @Getter private final Map<String, InstanceInfo> peers = new ConcurrentHashMap<>();
  private volatile Instant voteInitiationTime;
  private final CountDownLatch initializerLatch = new CountDownLatch(1);

  /**
   * Get the peers that are active with assigned order number > 0.
   *
   * @return Set of active peers
   */
  public Set<InstanceInfo> getAssignedPeers() {
    return peers.values().stream()
        .filter(instanceInfo -> instanceInfo.getOrder() > 0)
        .collect(Collectors.toSet());
  }

  /** Initiates peer management after application context gets refreshed */
  @EventListener(ContextRefreshedEvent.class)
  public void initialize() {
    peers.clear();
    peers.putAll(discoverPeers());
    if (peers.isEmpty()) {
      // No peers, so we immediately usurp the highest order
      activate(ORDER_HIGHEST);
    } else {
      selfInfo.setState(STATE_INTRODUCED);
      vote();
    }
    initializerLatch.countDown();
  }

  @Override
  public synchronized Object handle(InstanceEvent event, MessageHeaders headers) {

    // No event processing takes places until the instance is fully initialized.
    // Note that we may start receiving events from peers before that controller is actually
    // ready.
    try {
      initializerLatch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    log.trace("Received {}", event);

    // Take the note of the sender whatever the event type
    final InstanceInfo sender = mapper.eventToInfo(event);
    sender.setLast(Instant.now());
    peers.put(event.getId(), sender);

    if (EVENT_VOTE.equals(event.getEvent())) {
      final String candidateId = getEventProperty(event, PROPERTY_CANDIDATE);
      if (candidateId.equals(selfInfo.getId())) {
        // Register response from a peer to our vote request
        ballots.put(event.getId(), event);
      } else {
        // Respond to the candidate peer that requested it
        vote(peers.get(candidateId));
      }
    }

    if (EVENT_MESSAGE.equals(event.getEvent())) {
      Map<String, String> properties = event.getProperties();
      if (properties != null && properties.containsKey(PROPERTY_MESSAGE_ID)) {
        String messageId = properties.get(PROPERTY_MESSAGE_ID);
        properties.remove(PROPERTY_MESSAGE_ID);
        eventPublisher.publishEvent(new InstanceMessageEvent(this, sender, messageId, properties));
      } else {
        log.warn("Invalid event format {}. Missing {} property.", event, PROPERTY_MESSAGE_ID);
      }
    }

    // Null must be returned in our configuration as we do not have any channels configured
    return null;
  }

  /** Method scheduled by {@link InstanceController#configureTasks(ScheduledTaskRegistrar)} */
  public void heartbeat() {
    // Make sure we don't send anything until we are fully initialized.
    // Note that initialization occurs on application context refresh,
    // but this scheduler may be triggered earlier depending on speed od application startup.
    if (initializerLatch.getCount() > 0) {
      return;
    }
    checkPeers();
    checkBallots();
    notifyPeers(prepareHeartbeatEvent(), peers.values());
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
    // Use that implementation of scheduling instead of @Scheduled annotation
    // for better configurability.
    taskRegistrar.setScheduler(Executors.newSingleThreadScheduledExecutor());
    taskRegistrar.addTriggerTask(
        this::heartbeat,
        context -> {
          Optional<Date> lastCompletionTime = Optional.ofNullable(context.lastCompletionTime());
          Instant nextExecutionTime =
              lastCompletionTime
                  .orElseGet(Date::new)
                  .toInstant()
                  .plusMillis(config.getHeartbeatIntervalMillis());
          return Date.from(nextExecutionTime);
        });
  }

  @Override
  public void contribute(Info.Builder builder) {
    try {
      Map<String, Object> details = new HashMap<>();
      details.put("self", selfInfo);
      details.put("peers", peers.values());
      builder.withDetail("instances", details);
    } catch (Exception e) {
      log.warn("Failed to produce instance info", e);
    }
  }

  /**
   * Sends a message to all peers.
   *
   * @param id Message id
   */
  public void broadcastMessage(@NotNull final String id) {
    broadcastMessage(id, null);
  }

  /**
   * Sends a message to all peers.
   *
   * @param id Message id
   * @param properties Message properties
   */
  public void broadcastMessage(
      @NotNull final String id, @Nullable final Map<String, String> properties) {
    notifyPeers(prepareMessageEvent(id, properties), peers.values());
  }

  /**
   * Sends a message to one peer.
   *
   * @param destination The peer to send to
   * @param id Message id
   */
  public void sendMessage(@NotNull InstanceInfo destination, @NotNull final String id) {
    sendMessage(destination, id, null);
  }

  /**
   * Sends a message to one peer.
   *
   * @param destination The peer to send to
   * @param id Message id
   * @param properties Message properties
   */
  public void sendMessage(
      @NotNull InstanceInfo destination,
      @NotNull final String id,
      @Nullable final Map<String, String> properties) {
    notifyPeers(prepareMessageEvent(id, properties), List.of(destination));
  }

  private InstanceEvent prepareMessageEvent(
      @NotNull final String id, @Nullable final Map<String, String> properties) {
    Map<String, String> props = new HashMap<>();
    props.put(PROPERTY_MESSAGE_ID, id);
    if (properties != null) {
      props.putAll(properties);
    }
    return prepareHeartbeatEvent().toBuilder().event(EVENT_MESSAGE).properties(props).build();
  }

  private void checkPeers() {
    final List<InstanceInfo> absentPeers =
        peers.values().stream()
            .filter(
                peer ->
                    Duration.between(peer.getLast(), Instant.now()).toMillis()
                        > config.getHeartbeatTimeoutMillis())
            .collect(Collectors.toList());
    if (!absentPeers.isEmpty()) {
      // Firstly, confirm that the absent peer disappeared from Kubernetes
      final Map<String, InstanceInfo> discoveredPeers = discoverPeers();
      absentPeers.forEach(
          absentPeer -> {
            if (discoveredPeers.containsKey(absentPeer.getId())) {
              log.warn(
                  "Heartbeat timeout occurred for pod {} with IP={}, "
                      + "but it is still reported by Kubernetes",
                  absentPeer.getName(),
                  absentPeer.getIp());
              peers.get(absentPeer.getId()).setState(STATE_ABSENT);
              // What to do if the problem persists? Give it a bit more time and permanently remove?
              // For now, such an instance will be kept in the pool
            } else {
              log.warn(
                  "Removing absent pod {} with IP={}", absentPeer.getName(), absentPeer.getIp());
              peers.remove(absentPeer.getId());
              eventPublisher.publishEvent(new InstanceRemovedEvent(this, absentPeer));
              if (absentPeer.getOrder() > ORDER_UNASSIGNED && selfInfo.inState(STATE_SPARE)) {
                vote();
              }
            }
          });
    }
  }

  /** Sends a vote request to all peers */
  private void vote() {
    Map<String, String> props = new HashMap<>();
    props.put(PROPERTY_CANDIDATE, selfInfo.getId());
    props.put(PROPERTY_ORDER, Integer.toString(resolveOrder(selfInfo)));
    final InstanceEvent voteEvent =
        prepareHeartbeatEvent().toBuilder().event(EVENT_VOTE).properties(props).build();
    ballots.clear();
    voteInitiationTime = Instant.now();
    notifyPeers(voteEvent, peers.values());
  }

  /**
   * Sends vote response to the requester
   *
   * @param candidate Vote requester
   */
  private void vote(final InstanceInfo candidate) {
    Map<String, String> props = new HashMap<>();
    props.put(PROPERTY_CANDIDATE, candidate.getId());
    props.put(PROPERTY_ORDER, Integer.toString(resolveOrder(candidate)));
    notifyPeers(
        prepareHeartbeatEvent().toBuilder().event(EVENT_VOTE).properties(props).build(),
        List.of(candidate));
  }

  /** Check if we got all votes and we have consensus */
  private void checkBallots() {

    if (ballots.isEmpty()
        || peers.values().stream().anyMatch(peer -> peer.inState(STATE_DISCOVERED))) {
      return;
    }

    if (Duration.between(voteInitiationTime, Instant.now()).toMillis()
        > config.getHeartbeatTimeoutMillis()) {
      log.warn("Stale ballot? Voting again...");
      vote();
      return;
    }

    if (ballots.size() == peers.size()) {
      int updatedSelfOrder = resolveOrder(selfInfo);
      boolean consensus =
          ballots.values().stream()
              .allMatch(
                  event -> {
                    int proposedOrder =
                        Integer.parseInt(
                            getEventProperty(ballots.get(event.getId()), PROPERTY_ORDER));
                    return updatedSelfOrder == proposedOrder;
                  });
      if (consensus) {
        ballots.clear();
        voteInitiationTime = null;
        if (updatedSelfOrder == ORDER_UNASSIGNED) {
          log.warn("Pool of instances exhausted. Marking this instance spare");
          selfInfo.setState(STATE_SPARE);
        } else {
          log.info("Consensus reached. Activating this instance with order #{}", updatedSelfOrder);
          activate(updatedSelfOrder);
        }
      } else {
        log.warn("No consensus. Voting again...");
        vote();
      }
    }
  }

  private void activate(int order) {
    selfInfo.setOrder(order);
    selfInfo.setState(STATE_ACTIVE);
    notifyPeers(prepareHeartbeatEvent(), peers.values());
    eventPublisher.publishEvent(new InstanceReadyEvent(this, selfInfo));
  }

  private InstanceEvent prepareHeartbeatEvent() {
    return mapper.infoToEvent(selfInfo).toBuilder().event(EVENT_HELLO).build();
  }

  private void notifyPeers(final InstanceEvent event, Collection<InstanceInfo> peers) {
    try {
      peers.forEach(
          peer -> {
            boolean sent = false;
            try {
              sent =
                  outUdpAdapter
                      .getInputChannel()
                      .send(
                          MessageBuilder.withPayload(event)
                              .setHeader(
                                  HEADER_TARGET,
                                  String.format(
                                      "udp://%s:%d", peer.getIp(), config.getListenerPort()))
                              .build());
            } catch (Exception e) {
              log.error("Failed to send event", e);
            }
            if (sent) {
              log.trace("Sent {} to {}", event, peer.getIp());
            } else {
              peer.setState(STATE_ABSENT);
              log.trace(
                  "Failed to notify pod {} with IP={}, marking as absent",
                  peer.getName(),
                  peer.getIp());
            }
          });
    } catch (Exception e) {
      log.error("Failed to send instance notification", e);
    }
  }

  private Map<String, InstanceInfo> discoverPeers() {
    final Map<String, InstanceInfo> discoveredPeers = new HashMap<>();
    final List<ServiceInstance> instances = discoveryClient.getInstances(config.getServiceName());
    instances.stream()
        .filter(serviceInstance -> !serviceInstance.getInstanceId().equals(selfInfo.getId()))
        .forEach(
            serviceInstance -> {
              final InstanceInfo info =
                  InstanceInfo.builder()
                      .id(serviceInstance.getInstanceId())
                      .weight(0)
                      .name(UNKNOWN)
                      .ip(serviceInstance.getHost())
                      .namespace(UNKNOWN)
                      .order(ORDER_UNASSIGNED)
                      .state(STATE_DISCOVERED)
                      .last(Instant.now())
                      .build();
              discoveredPeers.put(info.getId(), info);
            });
    log.info(
        "Discovered peers: {}",
        Arrays.toString(discoveredPeers.values().toArray(new InstanceInfo[0])));
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
  private int resolveOrder(@NotNull final InstanceInfo candidate) {
    if (candidate.inNeitherState(STATE_INTRODUCED, STATE_SPARE)) {
      return candidate.inState(STATE_ACTIVE) ? candidate.getOrder() : ORDER_UNASSIGNED;
    }
    final List<Integer> takenOrderNumbers =
        Stream.concat(peers.values().stream(), Stream.of(selfInfo))
            .filter(
                peer ->
                    peer.inState(STATE_ACTIVE)
                        || (peer.inState(STATE_ABSENT) && peer.getOrder() > ORDER_UNASSIGNED))
            .map(InstanceInfo::getOrder)
            .collect(Collectors.toList());
    final List<Integer> availableOrderNumbers =
        IntStream.range(1, config.getPoolSize() + 1).boxed().collect(Collectors.toList());
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

  private String getEventProperty(final InstanceEvent event, final String name) {
    if (event == null
        || event.getProperties() == null
        || !event.getProperties().containsKey(name)) {
      throw new IllegalStateException(
          String.format("Expected %s property in %s", name, event));
    }
    return event.getProperties().get(name);
  }
}
