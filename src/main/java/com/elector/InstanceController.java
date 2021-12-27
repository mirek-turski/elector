package com.elector;

import static com.elector.Constant.EVENT_HELLO;
import static com.elector.Constant.EVENT_MESSAGE;
import static com.elector.Constant.EVENT_VOTE;
import static com.elector.Constant.HEADER_TARGET;
import static com.elector.Constant.ORDER_HIGHEST;
import static com.elector.Constant.ORDER_UNASSIGNED;
import static com.elector.Constant.PROPERTY_CANDIDATE;
import static com.elector.Constant.PROPERTY_MESSAGE_ID;
import static com.elector.Constant.PROPERTY_ORDER;
import static com.elector.Constant.STATE_ABSENT;
import static com.elector.Constant.STATE_ACTIVE;
import static com.elector.Constant.STATE_DISCOVERED;
import static com.elector.Constant.STATE_INTRODUCED;
import static com.elector.Constant.STATE_SPARE;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.handler.GenericHandler;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Manages instances of the service. The instances will be ordered starting from 0. Every new
 * instance will negotiate the highest available order number.
 */
public class InstanceController implements GenericHandler<ElectorEvent>, SchedulingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(InstanceController.class);

  private final ElectorProperties properties;
  private final InstanceInfo selfInfo;
  private final DiscoveryClient discoveryClient;
  private final IntegrationFlow outUdpAdapter;
  private final ApplicationEventPublisher eventPublisher;
  // Key = voter id
  private final Map<String, ElectorEvent> ballots = new ConcurrentHashMap<>();
  // Key = pod id
  private final Map<String, InstanceInfo> peers = new ConcurrentHashMap<>();
  private volatile Instant voteInitiationTime;
  private final CountDownLatch initializerLatch = new CountDownLatch(1);

  public InstanceController(
      ElectorProperties properties,
      InstanceInfo selfInfo,
      DiscoveryClient discoveryClient,
      IntegrationFlow outUdpAdapter,
      ApplicationEventPublisher eventPublisher) {
    this.properties = properties;
    this.selfInfo = selfInfo;
    this.discoveryClient = discoveryClient;
    this.outUdpAdapter = outUdpAdapter;
    this.eventPublisher = eventPublisher;
  }

  public Map<String, InstanceInfo> getPeers() {
    return this.peers;
  }

  /** Initiates peer management after application context gets refreshed */
  @EventListener(ContextRefreshedEvent.class)
  public void initialize() {
    Map<String, InstanceInfo> discoveredPeers = discoverPeers();
    peers.clear();
    peers.putAll(discoveredPeers);
    if (peers.isEmpty()) {
      // No peers, so we immediately usurp the highest order
      setInstanceReady(ORDER_HIGHEST, STATE_ACTIVE);
    } else {
      if (log.isDebugEnabled()) {
        log.debug(
            "Discovered peers: {}",
            Arrays.toString(discoveredPeers.values().toArray(new InstanceInfo[0])));
      }
      selfInfo.setState(STATE_INTRODUCED);
      vote();
    }
    initializerLatch.countDown();
  }

  @Override
  public synchronized Object handle(ElectorEvent event, MessageHeaders headers) {

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
    final InstanceInfo sender =
        InstanceInfo.builder()
            .id(event.getId())
            .host(event.getHost())
            .state(event.getState())
            .order(event.getOrder())
            .weight(event.getWeight())
            .build();
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
        // If this instance is spare request for voting from another instance should also trigger
        // vote for this one, but only if there is no vote in progress
        if (selfInfo.isSpare() && voteInitiationTime == null) {
          vote();
        }
      }
    }

    if (EVENT_MESSAGE.equals(event.getEvent())) {
      Map<String, String> eventProperties = event.getProperties();
      if (eventProperties != null && eventProperties.containsKey(PROPERTY_MESSAGE_ID)) {
        String messageId = eventProperties.get(PROPERTY_MESSAGE_ID);
        eventProperties.remove(PROPERTY_MESSAGE_ID);
        eventPublisher.publishEvent(new InstanceMessageEvent(this, sender, messageId, eventProperties));
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
                  .plusMillis(properties.getHeartbeatIntervalMillis());
          return Date.from(nextExecutionTime);
        });
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

  /**
   * Get the peers that are active with assigned order number greater than 0.
   *
   * @return Set of active peers
   */
  public Set<InstanceInfo> getAssignedPeers() {
    return peers.values().stream()
        .filter(instanceInfo -> instanceInfo.getOrder() > 0)
        .collect(Collectors.toSet());
  }

  /**
   * Marks this instance as unassigned and spare one and initiates election. Result of the election
   * is unknown.
   */
  public void resign() {
    selfInfo.setState(STATE_SPARE);
    selfInfo.setOrder(ORDER_UNASSIGNED);
    vote();
  }

  private ElectorEvent prepareMessageEvent(
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
                        > properties.getHeartbeatTimeoutMillis())
            .collect(Collectors.toList());
    if (!absentPeers.isEmpty()) {
      // Firstly, confirm that the absent peer disappeared from Kubernetes
      final Map<String, InstanceInfo> discoveredPeers = discoverPeers();
      absentPeers.forEach(
          absentPeer -> {
            if (!discoveredPeers.containsKey(absentPeer.getId())) {
              log.debug(
                  "Removing missing instance {} [{}]", absentPeer.getId(), absentPeer.getHost());
              peers.remove(absentPeer.getId());
              eventPublisher.publishEvent(new InstanceRemovedEvent(this, absentPeer));
              if (absentPeer.isAssigned() && selfInfo.inState(STATE_SPARE)) {
                vote();
              }
            } else if (absentPeer.inNeitherState(STATE_ABSENT)) {
              log.warn(
                  "Instance heartbeat timeout occurred for instance {} [{}], "
                      + "but it is still reported by discovery client",
                  absentPeer.getId(),
                  absentPeer.getHost());
              peers.get(absentPeer.getId()).setState(STATE_ABSENT);
              // What to do if the problem persists? Give it a bit more time and permanently remove?
              // For now, such an instance will be kept in the pool
            }
          });
    }
  }

  /** Sends a vote request to all peers */
  private void vote() {
    Map<String, String> props = new HashMap<>();
    props.put(PROPERTY_CANDIDATE, selfInfo.getId());
    props.put(PROPERTY_ORDER, Integer.toString(resolveOrder(selfInfo)));
    final ElectorEvent voteEvent =
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

    // No vote initiated by this instance
    if (voteInitiationTime == null) {
      return;
    }

    if (properties.isQuorumRequired()) {

      if (Duration.between(voteInitiationTime, Instant.now()).toMillis()
          > properties.getHeartbeatTimeoutMillis()) {
        log.debug("Stale ballot, voting again...");
        vote();
        return;
      }

      if (ballots.isEmpty()
          || peers.values().stream().anyMatch(peer -> peer.inState(STATE_DISCOVERED))) {
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
            log.debug("Pool of instances exhausted, marking this instance spare");
            setInstanceReady(ORDER_UNASSIGNED, STATE_SPARE);
          } else {
            log.debug("Consensus reached, activating this instance with order #{}", updatedSelfOrder);
            setInstanceReady(updatedSelfOrder, STATE_ACTIVE);
          }
        } else {
          log.debug("No consensus, voting again...");
          vote();
        }
      }
    } else {

      // TODO: Work out the case when not all peers voted in the time provided

    }

  }

  private void setInstanceReady(int order, final String state) {
    if (selfInfo.getOrder() != order || !selfInfo.inState(state)) {
      selfInfo.setOrder(order);
      selfInfo.setState(state);
      log.info("This {}", selfInfo);
      notifyPeers(prepareHeartbeatEvent(), peers.values());
      eventPublisher.publishEvent(new InstanceReadyEvent(this, selfInfo));
    }
  }

  private ElectorEvent prepareHeartbeatEvent() {
    return ElectorEvent.builder()
        .event(EVENT_HELLO)
        .id(selfInfo.getId())
        .host(selfInfo.getHost())
        .state(selfInfo.getState())
        .order(selfInfo.getOrder())
        .weight(selfInfo.getWeight())
        .build();
  }

  private void notifyPeers(final ElectorEvent event, Collection<InstanceInfo> peers) {
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
                                      "udp://%s:%d", peer.getHost(), properties.getListenerPort()))
                              .build());
            } catch (Exception e) {
              log.error("Failed to send event", e);
            }
            if (sent) {
              log.trace("Sent {} to {}", event, peer.getHost());
            } else {
              peer.setState(STATE_ABSENT);
              log.debug(
                  "Failed to notify instance {} [{}], marking as absent",
                  peer.getId(),
                  peer.getHost());
            }
          });
    } catch (Exception e) {
      log.error("Failed to send instance notification", e);
    }
  }

  private Map<String, InstanceInfo> discoverPeers() {
    final Map<String, InstanceInfo> discoveredPeers = new HashMap<>();
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
  private int resolveOrder(@NotNull final InstanceInfo candidate) {
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

  private String getEventProperty(final ElectorEvent event, final String name) {
    if (event == null
        || event.getProperties() == null
        || !event.getProperties().containsKey(name)) {
      throw new IllegalStateException(String.format("Expected %s property in %s", name, event));
    }
    return event.getProperties().get(name);
  }
}
