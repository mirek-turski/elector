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
import static com.elector.Constant.STATE_INTRODUCED;
import static com.elector.Constant.STATE_NEW;
import static com.elector.Constant.STATE_SPARE;

import com.elector.ElectorProperties.BallotType;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.annotation.PreDestroy;
import javax.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.handler.GenericHandler;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;

/**
 * Handles communication between instances of the service. The instances will be ordered starting from 0. Every new
 * instance will negotiate the highest available order number.
 */
public class InstanceController implements GenericHandler<ElectorEvent> {

  private static final Logger log = LoggerFactory.getLogger(InstanceController.class);

  private final ElectorProperties properties;
  private final InstanceRegistry registry;
  private final IntegrationFlow outUdpAdapter;
  private final ApplicationEventPublisher eventPublisher;
  // Key = voter id
  private final Map<String, ElectorEvent> ballots = new ConcurrentHashMap<>();
  private volatile Instant voteInitiationTime;
  private final CountDownLatch initializerLatch = new CountDownLatch(1);
  private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
  private final ScheduledExecutorService ballotScheduler = Executors.newSingleThreadScheduledExecutor();

  public InstanceController(
      ElectorProperties properties,
      InstanceRegistry registry,
      @Qualifier("electorOutUdpAdapter") IntegrationFlow outUdpAdapter,
      ApplicationEventPublisher eventPublisher) {
    this.properties = properties;
    this.registry = registry;
    this.outUdpAdapter = outUdpAdapter;
    this.eventPublisher = eventPublisher;
  }

  /** Initiates peer management after application context gets refreshed */
  @EventListener(ContextRefreshedEvent.class)
  public void initialize() {
    Map<String, InstanceInfo> discoveredPeers = registry.discoverPeers();
    registry.getPeers().clear();
    registry.getPeers().putAll(discoveredPeers);
    if (registry.getPeers().isEmpty()) {
      // No peers, so we immediately usurp the highest order
      log.debug("No peer instances discovered. Claiming the highest order number.");
      setInstanceReady(ORDER_HIGHEST, STATE_ACTIVE);
    } else {
      if (log.isDebugEnabled()) {
        log.debug("Discovered peers: {}", Arrays.toString(discoveredPeers.values().toArray(new InstanceInfo[0])));
      }
      registry.getSelfInfo().setState(STATE_INTRODUCED);
      vote();
    }
    heartbeatScheduler.scheduleAtFixedRate(
        this::heartbeat, 0L, properties.getHeartbeatIntervalMillis(), TimeUnit.MILLISECONDS);
    initializerLatch.countDown();
  }

  @PreDestroy
  public void deactivate() {
    heartbeatScheduler.shutdownNow();
    ballotScheduler.shutdownNow();
    registry.getPeers().clear();
    ballots.clear();
    voteInitiationTime = null;
    registry.getSelfInfo().setState(STATE_NEW);
    registry.getSelfInfo().setOrder(ORDER_UNASSIGNED);
  }

  @Override
  public synchronized Object handle(ElectorEvent event, MessageHeaders headers) {

    // No event processing takes place until the instance is fully initialized.
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
    if (registry.getPeers().containsKey(sender.getId()) && registry.getPeers().get(sender.getId()).inState(STATE_ABSENT)) {
      log.info(
          "Instance {} [{}] is back online in {} state",
          sender.getId(),
          sender.getHost(),
          sender.getState());
    }
    registry.getPeers().put(sender.getId(), sender);

    if (EVENT_VOTE.equals(event.getEvent())) {
      final String candidateId = getEventProperty(event, PROPERTY_CANDIDATE);
      if (candidateId.equals(registry.getSelfInfo().getId())) {
        // Register response from a peer to our vote request
        ballots.put(event.getId(), event);
      } else {
        // Respond to the candidate peer that requested it
        vote(registry.getPeers().get(candidateId));
        // If this instance is spare request for voting from another instance should also trigger
        // vote for this one, but only if there is no vote in progress
        if (registry.getSelfInfo().isSpare() && voteInitiationTime == null) {
          vote();
        }
      }
    }

    if (EVENT_MESSAGE.equals(event.getEvent())) {
      Map<String, String> eventProperties = event.getProperties();
      if (eventProperties != null && eventProperties.containsKey(PROPERTY_MESSAGE_ID)) {
        String messageId = eventProperties.get(PROPERTY_MESSAGE_ID);
        eventProperties.remove(PROPERTY_MESSAGE_ID);
        eventPublisher.publishEvent(
            new InstanceMessageEvent(this, sender, messageId, eventProperties));
      } else {
        log.warn("Invalid event format {}. Missing {} property.", event, PROPERTY_MESSAGE_ID);
      }
    }

    // Null must be returned in our configuration as we do not have any channels configured
    return null;
  }

  /** Method scheduled by {@link InstanceController#heartbeatScheduler} */
  public void heartbeat() {
    checkPeers();
    notifyPeers(prepareHeartbeatEvent(), registry.getPeers().values());
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
    notifyPeers(prepareMessageEvent(id, properties), registry.getPeers().values());
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
    return registry.getPeers().values().stream()
        .filter(instanceInfo -> instanceInfo.getOrder() > 0)
        .collect(Collectors.toSet());
  }

  /**
   * Marks this instance as unassigned and spare one and initiates election. Result of the election
   * is unknown.
   */
  public void resign() {
    registry.getSelfInfo().setState(STATE_SPARE);
    registry.getSelfInfo().setOrder(ORDER_UNASSIGNED);
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
        registry.getPeers().values().stream()
            .filter(
                peer ->
                    Duration.between(peer.getLast(), Instant.now()).toMillis()
                        > properties.getHeartbeatTimeoutMillis())
            .collect(Collectors.toList());
    if (!absentPeers.isEmpty()) {
      // Firstly, confirm that the absent peer is not discoverable (i.e. disappeared from
      // Kubernetes).
      final Map<String, InstanceInfo> discoveredPeers = registry.discoverPeers();
      absentPeers.forEach(
          absentPeer -> {
            if (!discoveredPeers.containsKey(absentPeer.getId())) {
              log.debug(
                  "Removing missing instance {} [{}]", absentPeer.getId(), absentPeer.getHost());
              registry.getPeers().remove(absentPeer.getId());
              eventPublisher.publishEvent(new InstanceRemovedEvent(this, absentPeer));
              if (absentPeer.isAssigned() && registry.getSelfInfo().inState(STATE_SPARE)) {
                vote();
              }
            } else if (absentPeer.inNeitherState(STATE_ABSENT)) {
              log.warn(
                  "Heartbeat timeout occurred for instance {} [{}], but it is still reported by discovery client. "
                      + "Marking as absent.",
                  absentPeer.getId(),
                  absentPeer.getHost());
              registry.getPeers().get(absentPeer.getId()).setState(STATE_ABSENT);
              // What to do if the problem persists? Give it a bit more time and permanently remove?
              // For now, such an instance will be kept in the pool
            }
          });
    }
  }

  /** Sends a vote request to all peers */
  private void vote() {
    Map<String, String> props = new HashMap<>();
    props.put(PROPERTY_CANDIDATE, registry.getSelfInfo().getId());
    props.put(PROPERTY_ORDER, Integer.toString(registry.resolveOrder(registry.getSelfInfo())));
    final ElectorEvent voteEvent =
        prepareHeartbeatEvent().toBuilder().event(EVENT_VOTE).properties(props).build();
    ballots.clear();
    voteInitiationTime = Instant.now();
    notifyPeers(voteEvent, registry.getPeers().values());
    ballotScheduler.schedule(
        this::checkBallots, properties.getBallotTimeoutMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Sends vote response to the requester
   *
   * @param candidate Vote requester
   */
  private void vote(final InstanceInfo candidate) {
    Map<String, String> props = new HashMap<>();
    props.put(PROPERTY_CANDIDATE, candidate.getId());
    props.put(PROPERTY_ORDER, Integer.toString(registry.resolveOrder(candidate)));
    notifyPeers(
        prepareHeartbeatEvent().toBuilder().event(EVENT_VOTE).properties(props).build(),
        List.of(candidate));
  }

  /** Check if we got all votes and we have consensus */
  private void checkBallots() {
    if (properties.getBallotType().equals(BallotType.TIMED)) {
      log.debug("Electing in timed ballot");
      elect();
    } else if (properties.getBallotType().equals(BallotType.QUORUM)) {
      // Plus one for self-vote
      if (ballots.size() + 1 >= properties.getPoolSize()) {
        log.debug("Electing in quorum ballot");
        elect();
      } else {
        log.debug("Stale quorum ballot, voting again");
        vote();
      }
    } else if (properties.getBallotType().equals(BallotType.UNANIMOUS)) {
      if (ballots.size() == registry.getPeers().size()) {
        log.debug("Electing in unanimous ballot...");
        elect();
      } else {
        log.debug("Stale unanimous ballot, voting again...");
        vote();
      }
    }
  }

  private void elect() {
    int updatedSelfOrder = registry.resolveOrder(registry.getSelfInfo());
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
        log.debug("Ballot finished, activating this instance with order #{}", updatedSelfOrder);
        setInstanceReady(updatedSelfOrder, STATE_ACTIVE);
      }
    } else {
      log.debug("No consensus, voting again...");
      vote();
    }
  }

  private void setInstanceReady(int order, final String state) {
    if (registry.getSelfInfo().getOrder() != order || !registry.getSelfInfo().inState(state)) {
      registry.getSelfInfo().setOrder(order);
      registry.getSelfInfo().setState(state);
      log.info("This {}", registry.getSelfInfo());
      notifyPeers(prepareHeartbeatEvent(), registry.getPeers().values());
      eventPublisher.publishEvent(new InstanceReadyEvent(this, registry.getSelfInfo()));
    }
  }

  private ElectorEvent prepareHeartbeatEvent() {
    return ElectorEvent.builder()
        .event(EVENT_HELLO)
        .id(registry.getSelfInfo().getId())
        .host(registry.getSelfInfo().getHost())
        .state(registry.getSelfInfo().getState())
        .order(registry.getSelfInfo().getOrder())
        .weight(registry.getSelfInfo().getWeight())
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
                                  String.format("udp://%s:%d", peer.getHost(), properties.getListenerPort()))
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

  private String getEventProperty(final ElectorEvent event, final String name) {
    if (event == null
        || event.getProperties() == null
        || !event.getProperties().containsKey(name)) {
      throw new IllegalStateException(String.format("Expected %s property in %s", name, event));
    }
    return event.getProperties().get(name);
  }
}
