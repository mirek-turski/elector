package com.elector;

import static com.elector.Constant.HEADER_TARGET;
import static com.elector.Constant.ORDER_HIGHEST;
import static com.elector.Constant.ORDER_UNASSIGNED;
import static com.elector.Constant.STATE_ABSENT;
import static com.elector.Constant.STATE_ACTIVE;
import static com.elector.Constant.STATE_DISCOVERED;
import static com.elector.Constant.STATE_INTRODUCED;
import static com.elector.Constant.STATE_NEW;
import static com.elector.Constant.STATE_SPARE;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import com.elector.ElectorProperties.BallotType;
import com.elector.utils.LogUtils;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.validation.constraints.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlowDefinition;
import org.springframework.messaging.MessageChannel;
import org.springframework.test.util.ReflectionTestUtils;

public class InstanceControllerTest {

  private static final Logger log = LogUtils.createConsoleLogger("com.elector", Level.TRACE);

  private final ElectorProperties properties = new ElectorProperties();
  final InstanceController controller = new InstanceController(properties, null, null, null, null);

  InstanceInfo self =
      InstanceInfo.builder().weight(1233L).order(ORDER_UNASSIGNED).state(STATE_INTRODUCED).build();

  InstanceInfo introducedInstance =
      InstanceInfo.builder().weight(1234L).order(ORDER_UNASSIGNED).state(STATE_INTRODUCED).build();

  private static InstanceInfo getInfo(InstanceController controller) {
    return (InstanceInfo) ReflectionTestUtils.getField(controller, "selfInfo");
  }
  
  private TestEnvironment env;
  
  @AfterEach
  public void afterEachTest() {
    if (env != null) {
      env.clean();
      env = null;
    }
  }

  @Test
  public void testTimedBallot() {
    log.info("==================== Running testTimedBallot");
    env = TestEnvironment.builder().poolSize(2).ballotType(BallotType.TIMED).build();
    String ip1 = env.addPod();
    String ip2 = env.addPod();
    log.info(">>>>>> Starting 1st instance");
    Instant start = Instant.now();
    Set<InstanceController> controllers = env.startPods(true, ip1);
    assertTrue(Duration.between(start, Instant.now()).toMillis() > env.getProperties().getBallotTimeoutMillis());
    assertTrue(getInfo(controllers.stream().findFirst().get()).isActive());
    assertEquals(1, getInfo(controllers.stream().findFirst().get()).getOrder());
    log.info(">>>>>> Starting 2nd instance after {} millis", env.getProperties().getBallotTimeoutMillis());
    controllers = env.startPods(true, ip2);
    assertTrue(getInfo(controllers.stream().findFirst().get()).isActive());
    assertEquals(2, getInfo(controllers.stream().findFirst().get()).getOrder());
  }

  @Test
  public void testQuorumBallot() {
    log.info("==================== Running testQuorumBallot");
    env = TestEnvironment.builder().poolSize(2).ballotType(BallotType.QUORUM).build();
    String ip1 = env.addPod();
    String ip2 = env.addPod();
    log.info(">>>>>> Starting 1st instance");
    Set<InstanceController> controllers = new HashSet<>(env.startPods(false, ip1));
    await().timeout(env.getProperties().getBallotTimeoutMillis() * 2L, TimeUnit.MILLISECONDS);
    assertEquals(STATE_INTRODUCED, getInfo(controllers.stream().findFirst().get()).getState());
    assertEquals(0, getInfo(controllers.stream().findFirst().get()).getOrder());
    log.info(">>>>>> Starting 2nd instance");
    controllers.addAll(env.startPods(false, ip2));
    env.awaitActivation(controllers);
    assertWeightedOrder(getInfo(env.getController(ip1)), getInfo(env.getController(ip2)));
  }

  @Test
  public void testUnanimousBallot() {
    log.info("==================== Running testUnanimousBallot");
    env = TestEnvironment.builder().poolSize(2).ballotType(BallotType.UNANIMOUS).build();
    String ip1 = env.addPod();
    String ip2 = env.addPod();
    String ip3 = env.addPod();
    log.info(">>>>>> Starting two instances out of three discovered");
    Set<InstanceController> controllers = new HashSet<>(env.startPods(false, ip1, ip2));
    await().timeout(env.getProperties().getBallotTimeoutMillis() * 2L, TimeUnit.MILLISECONDS);
    controllers.forEach(controller -> {
      assertEquals(STATE_INTRODUCED, getInfo(controller).getState());
      assertEquals(0, getInfo(controller).getOrder());
    });
    log.info(">>>>>> Starting 3nd instance");
    controllers.addAll(env.startPods(false, ip3));
    env.awaitActivation(controllers);
  }

  @Test
  public void testLeaderElection() {
    log.info("==================== Running testLeaderElection");
    env = TestEnvironment.builder().poolSize(1).build();
    String ip1 = env.addPod();
    String ip2 = env.addPod();
    String ip3 = env.addPod();
    log.info(">>>>>> Electing leader from amongst three candidates");
    Set<InstanceController> controllers =
        env.startPods(true, ip1, ip2, ip3);
    final Set<InstanceInfo> instances =
        controllers.stream().map(InstanceControllerTest::getInfo).collect(Collectors.toSet());
    assertEquals(3, instances.size());
    List<InstanceInfo> leaders =
        instances.stream().filter(InstanceInfo::isLeader).collect(Collectors.toList());
    List<InstanceInfo> minions =
        instances.stream().filter(InstanceInfo::isMinion).collect(Collectors.toList());
    assertEquals(1, leaders.size());
    assertEquals(2, minions.size());
    log.info(">>>>>> Removing leader triggering re-election");
    env.deletePod(leaders.get(0).getHost());
    instances.clear();
    instances.addAll(minions);
    await()
        .atMost(3000, TimeUnit.MILLISECONDS)
        .until(() -> instances.stream().anyMatch(InstanceInfo::isLeader));
    leaders = instances.stream().filter(InstanceInfo::isLeader).collect(Collectors.toList());
    minions = instances.stream().filter(InstanceInfo::isMinion).collect(Collectors.toList());
    assertEquals(1, leaders.size());
    assertEquals(1, minions.size());
    log.info(">>>>>> Leader resigns triggering re-election");
    // Make sure the resigning leader does not get re-elected
    ReflectionTestUtils.setField(leaders.get(0), "weight", minions.get(0).getWeight() - 1);
    env.getController(leaders.get(0).getHost()).resign();
    await()
        .atMost(3000, TimeUnit.MILLISECONDS)
        .until(() -> instances.stream().anyMatch(InstanceInfo::isLeader));
    leaders = instances.stream().filter(InstanceInfo::isLeader).collect(Collectors.toList());
    minions = instances.stream().filter(InstanceInfo::isMinion).collect(Collectors.toList());
    assertEquals(1, leaders.size());
    assertEquals(1, minions.size());
  }

  @Test
  public void testHighestOrderWithNoPeers() {
    log.info("==================== Running testHighestOrderWithNoPeers");
    env = TestEnvironment.builder().build();
    String ip = env.addPod();
    env.startPods(true, ip);
    ArgumentCaptor<InstanceReadyEvent> argumentCaptor =
        ArgumentCaptor.forClass(InstanceReadyEvent.class);
    verify(env.getEventPublishers().get(ip), timeout(3000).times(1))
        .publishEvent(argumentCaptor.capture());
    InstanceInfo selfInfo = argumentCaptor.getValue().getSelfInfo();
    assertEquals(STATE_ACTIVE, selfInfo.getState());
    assertEquals(ORDER_HIGHEST, selfInfo.getOrder());
  }

  @Test
  public void testStartTwoInstancesSimultaneously() {
    log.info("==================== Running testStartTwoInstancesSimultaneously");
    env = TestEnvironment.builder().poolSize(2).build();
    String ip1 = env.addPod();
    String ip2 = env.addPod();
    env.startPods(true, ip1, ip2);

    ArgumentCaptor<ApplicationEvent> eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
    verify(env.getEventPublishers().get(ip1), timeout(3000).times(1))
        .publishEvent(eventCaptor.capture());
    verify(env.getEventPublishers().get(ip2), timeout(3000).times(1))
        .publishEvent(eventCaptor.capture());
    List<ApplicationEvent> events = eventCaptor.getAllValues();

    assertTrue(events.get(0) instanceof InstanceReadyEvent);
    assertTrue(events.get(1) instanceof InstanceReadyEvent);
    InstanceInfo i0 = ((InstanceReadyEvent) events.get(0)).getSelfInfo();
    InstanceInfo i1 = ((InstanceReadyEvent) events.get(1)).getSelfInfo();
    assertEquals(STATE_ACTIVE, i0.getState());
    assertEquals(STATE_ACTIVE, i1.getState());

    assertWeightedOrder(i0, i1);
  }

  @Test
  public void testSpareInstanceOnPoolExhausted() {
    log.info("==================== Running testSpareInstanceOnPoolExhausted");
    env = TestEnvironment.builder().poolSize(2).build();
    String ip1 = env.addPod();
    String ip2 = env.addPod();
    env.startPods(true, ip1, ip2);
    String ip3 = env.addPod();
    env.startPods(true, ip3);
    ArgumentCaptor<ApplicationEvent> eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
    verify(env.getEventPublishers().get(ip3), timeout(3000).times(1))
        .publishEvent(eventCaptor.capture());
    assertTrue(eventCaptor.getValue() instanceof InstanceReadyEvent);
    assertTrue(getInfo(env.getController(ip3)).inState(STATE_SPARE));
  }

  @Test
  public void testDeletedInstanceReplacedWithSpare() {
    log.info("==================== Running testDeletedInstanceReplacedWithSpare");
    env = TestEnvironment.builder().poolSize(2).build();
    log.info(">>>>>> Starting up two instances simultaneously");
    String ip1 = env.addPod();
    String ip2 = env.addPod();
    env.startPods(true, ip1, ip2);
    log.info(">>>>>> Starting spare instance");
    String ip3 = env.addPod();
    env.startPods(true, ip3);
    int orderToClaim = getInfo(env.getController(ip2)).getOrder();
    log.info(">>>>>> Removing second/active instance");
    env.deletePod(ip2);
    await()
        .atMost(properties.getHeartbeatTimeoutMillis() * 2L, TimeUnit.MILLISECONDS)
        .until(() -> getInfo(env.getController(ip3)).isActive());
    ArgumentCaptor<ApplicationEvent> eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
    verify(env.getEventPublishers().get(ip3), timeout(3000).times(3))
        .publishEvent(eventCaptor.capture());
    List<ApplicationEvent> events = eventCaptor.getAllValues();
    assertTrue(events.get(0) instanceof InstanceReadyEvent);
    assertTrue(events.get(1) instanceof InstanceRemovedEvent);
    assertTrue(events.get(2) instanceof InstanceReadyEvent);
    assertEquals(ip2, ((InstanceRemovedEvent) events.get(1)).getInstanceInfo().getHost());
    InstanceInfo activatedInstance = ((InstanceReadyEvent) events.get(2)).getSelfInfo();
    assertTrue(activatedInstance.isActive());
    assertEquals(ip3, activatedInstance.getHost());
    assertEquals(orderToClaim, activatedInstance.getOrder());
  }

  @Test
  public void testAbsentInstance() {
    log.info("==================== Running testAbsentInstance");
    env = TestEnvironment.builder().poolSize(2).build();
    log.info(">>>>>> Starting up two instances simultaneously");
    String ip1 = env.addPod();
    String ip2 = env.addPod();
    env.startPods(true, ip1, ip2);
    log.info(">>>>>> Starting spare instance");
    String ip3 = env.addPod();
    env.startPods(true, ip3);
    InstanceController controller1 = env.getController(ip1);
    InstanceController controller3 = env.getController(ip3);
    log.info(">>>>>> Muting instance #1");
    env.mutePod(ip1);
    await()
        .atMost(properties.getHeartbeatTimeoutMillis(), TimeUnit.MILLISECONDS)
        .until(
            () -> controller3.getPeers().get(getInfo(controller1).getId()).inState(STATE_ABSENT));
    assertTrue(getInfo(controller3).inState(STATE_SPARE));
    log.info(">>>>>> Unmuting instance #1");
    env.unmutePod(ip1);
    await()
        .atMost(properties.getHeartbeatTimeoutMillis(), TimeUnit.MILLISECONDS)
        .until(() -> controller3.getPeers().get(getInfo(controller1).getId()).isActive());
    assertTrue(getInfo(controller3).inState(STATE_SPARE));
  }

  @Test
  public void testResolveOrderInvalidCandidateState() {
    Integer order =
        ReflectionTestUtils.invokeMethod(
            controller, "resolveOrder", self.toBuilder().order(12).state(STATE_ACTIVE).build());
    assertEquals(12, order);
    order =
        ReflectionTestUtils.invokeMethod(
            controller, "resolveOrder", self.toBuilder().state(STATE_DISCOVERED).build());
    assertEquals(0, order);
  }

  @Test
  public void testResolveOrderSelfNoPeers() {
    properties.setPoolSize(1);
    assertOrder(1, self, self, null, null);
  }

  @Test
  public void testResolveOrderSelfReadyPeerIntroduced() {
    properties.setPoolSize(2);
    assertOrder(2, self.toBuilder().order(1).state(STATE_ACTIVE).build(), introducedInstance, null, null);
    assertOrder(1, self.toBuilder().order(2).state(STATE_ACTIVE).build(), introducedInstance, null, null);
  }

  @Test
  public void testResolveOrderSelfDiscoveredPeer() {
    properties.setPoolSize(2);
    final InstanceInfo discoveredInstance =
        InstanceInfo.builder().weight(0).order(0).state(STATE_DISCOVERED).build();
    assertOrder(1, self, self, List.of(discoveredInstance), null);
  }

  @Test
  public void testResolveOrderSelfPeersReady() {
    properties.setPoolSize(4);
    assertOrder(3, self, self, null, null, 1, 2, 4);
    assertOrder(2, self, self, null, null, 1, 3, 4);
    assertOrder(1, self, self, null, null, 2, 3, 4);
    assertOrder(4, self, self, null, null, 1, 2, 3);
    assertOrder(0, self, self, null, null, 1, 2, 3, 4);
  }

  @Test
  public void testResolveOrderWeighted() {
    properties.setPoolSize(4);

    assertOrder(0, self, self, null, List.of(introducedInstance.toBuilder().weight(1234L).build()), 1, 2, 4);
    assertOrder(3, self, self, null, List.of(introducedInstance.toBuilder().weight(1232L).build()), 1, 2, 4);
    assertOrder(0, self, self, null, List.of(introducedInstance.toBuilder().weight(1234L).build()), 1, 2, 3);
    assertOrder(4, self, self, null, List.of(introducedInstance.toBuilder().weight(1232L).build()), 1, 2, 3);
    assertOrder(0, self, self, null, List.of(introducedInstance.toBuilder().weight(1234L).build()), 2, 3, 4);
    assertOrder(1, self, self, null, List.of(introducedInstance.toBuilder().weight(1232L).build()), 2, 3, 4);

    assertOrder(3, self, introducedInstance.toBuilder().weight(1234L).build(), null, null, 1, 2, 4);
    assertOrder(0, self, introducedInstance.toBuilder().weight(1232L).build(), null, null, 1, 2, 4);
    assertOrder(4, self, introducedInstance.toBuilder().weight(1234L).build(), null, null, 1, 2, 3);
    assertOrder(0, self, introducedInstance.toBuilder().weight(1232L).build(), null, null, 1, 2, 3);
    assertOrder(1, self, introducedInstance.toBuilder().weight(1234L).build(), null, null, 2, 3, 4);
    assertOrder(0, self, introducedInstance.toBuilder().weight(1232L).build(), null, null, 2, 3, 4);
  }

  private void assertOrder(
      int expected,
      @NotNull InstanceInfo self,
      @NotNull InstanceInfo candidate,
      List<InstanceInfo> discovered,
      List<InstanceInfo> introduced,
      int... readyOrderValues) {
    Map<String, InstanceInfo> peers = new HashMap<>();
    for (int readyOrderValue : readyOrderValues) {
      peers.put(
              UUID.randomUUID().toString(),
              InstanceInfo.builder()
                      .weight((long) (Math.random() * 1000))
                      .order(readyOrderValue)
                      .state(STATE_ACTIVE)
                      .build());
    }
    if (!self.equals(candidate)) {
      peers.put(UUID.randomUUID().toString(), candidate);
    }
    if (introduced != null) {
      introduced.forEach(peer -> peers.put(UUID.randomUUID().toString(), peer));
    }
    if (discovered != null) {
      discovered.forEach(peer -> peers.put(UUID.randomUUID().toString(), peer));
    }
    ReflectionTestUtils.setField(controller, "selfInfo", self);
    ReflectionTestUtils.setField(controller, "peers", peers);
    int result = ReflectionTestUtils.invokeMethod(controller, "resolveOrder", candidate);
    assertEquals(expected, result);
  }

  private void assertWeightedOrder(InstanceInfo...instances) {
    Set<InstanceInfo> weightedInstances =
        Arrays.stream(instances)
            .sorted(Comparator.comparingLong(InstanceInfo::getWeight).reversed())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    final AtomicInteger expectedOrder = new AtomicInteger(1);
    weightedInstances.stream().forEach(instanceInfo -> {
      assertEquals(expectedOrder.getAndIncrement(), instanceInfo.getOrder());
    });
  }

  private static class TestEnvironment {

    private final EventDispatcher eventDispatcher = new EventDispatcher();
    private final DiscoveryClientStub discoveryClient = new DiscoveryClientStub();
    private final Map<String, DefaultServiceInstance> serviceInstances = new HashMap<>();
    private final Map<String, ApplicationEventPublisher> eventPublishers = new HashMap<>();
    private final ElectorProperties properties;
    private int newIp;

    private TestEnvironment(int newIp, ElectorProperties properties) {
      this.newIp = newIp;
      this.properties = properties;
    }

    public static TestEnvironmentBuilder builder() {
      return new TestEnvironmentBuilder();
    }

    public InstanceController getController(String ip) {
      return eventDispatcher.getControllers().get(ip);
    }

    public String addPod() {
      final String uuid = UUID.randomUUID().toString();
      final String ip = "10.1.0.10" + newIp++;
      final InstanceInfo selfInfo =
          InstanceInfo.builder()
              .id(uuid)
              .weight((long) (System.currentTimeMillis() * Math.random()))
              .host(ip)
              .order(ORDER_UNASSIGNED)
              .state(STATE_NEW)
              .last(Instant.now())
              .build();

      final DefaultServiceInstance serviceInstance = new DefaultServiceInstance();
      serviceInstance.setInstanceId(uuid);
      serviceInstance.setHost(ip);
      final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

      final InstanceController instanceController =
          new InstanceController(
                  properties, selfInfo, discoveryClient, eventDispatcher, eventPublisher);
      serviceInstances.put(ip, serviceInstance);
      eventPublishers.put(ip, eventPublisher);
      eventDispatcher.addPod(ip, instanceController);
      discoveryClient.addInstances(serviceInstance);

      return ip;
    }

    public Set<InstanceController> startPods(boolean expectActivation, String... ips) {
      Set<InstanceController> controllers = new HashSet<>();
      Arrays.stream(ips)
          .forEach(
              ip -> {
                InstanceController controller = getController(ip);
                controller.initialize();
                controllers.add(controller);
              });
      if (expectActivation) {
        awaitActivation(controllers);
      }
      return controllers;
    }

    public void mutePod(String ip) {
      ReflectionTestUtils.setField(getController(ip), "outUdpAdapter", new EventDispatcher());
    }

    public void unmutePod(String ip) {
      ReflectionTestUtils.setField(getController(ip), "outUdpAdapter", eventDispatcher);
    }

    public void deletePod(String ip) {
      ReflectionTestUtils.setField(getController(ip), "discoveryClient", new DiscoveryClientStub());
      getController(ip).deactivate();
      discoveryClient.removeInstance(serviceInstances.get(ip));
      eventPublishers.remove(ip);
      serviceInstances.remove(ip);
      eventDispatcher.removePod(ip);
    }
    
    public void clean() {
      Set<String> ips = new HashSet<>(serviceInstances.keySet());
      ips.forEach(this::deletePod);
    }

    public void awaitActivation(Set<InstanceController> controllers) {
      await()
          .atMost(3000, TimeUnit.MILLISECONDS)
          .until(
              () ->
                  controllers.stream()
                      .allMatch(
                          controller ->
                              getInfo(controller).inEitherState(STATE_ACTIVE, STATE_SPARE)));
    }

    public EventDispatcher getEventDispatcher() {
      return this.eventDispatcher;
    }

    public DiscoveryClientStub getDiscoveryClient() {
      return this.discoveryClient;
    }

    public Map<String, DefaultServiceInstance> getServiceInstances() {
      return this.serviceInstances;
    }

    public Map<String, ApplicationEventPublisher> getEventPublishers() {
      return this.eventPublishers;
    }

    public ElectorProperties getProperties() {
      return this.properties;
    }

    public int getNewIp() {
      return this.newIp;
    }

    public static class TestEnvironmentBuilder {
      private int newIp = 1;
      private int heartbeatIntervalMillis = 200;
      private int heartbeatTimeoutMillis = 500;
      private int ballotTimeoutMillis = 500;
      private BallotType ballotType = BallotType.QUORUM;
      private int poolSize = 1;

      TestEnvironmentBuilder() {}

      public TestEnvironmentBuilder newIp(int newIp) {
        this.newIp = newIp;
        return this;
      }

      public TestEnvironmentBuilder heartbeatIntervalMillis(int heartbeatIntervalMillis) {
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        return this;
      }

      public TestEnvironmentBuilder heartbeatTimeoutMillis(int heartbeatTimeoutMillis) {
        this.heartbeatTimeoutMillis = heartbeatTimeoutMillis;
        return this;
      }

      public TestEnvironmentBuilder ballotTimeoutMillis(int ballotTimeoutMillis) {
        this.ballotTimeoutMillis = ballotTimeoutMillis;
        return this;
      }

      public TestEnvironmentBuilder poolSize(int poolSize) {
        this.poolSize = poolSize;
        return this;
      }

      public TestEnvironmentBuilder ballotType(BallotType ballotType) {
        this.ballotType = ballotType;
        return this;
      }

      public TestEnvironment build() {
        ElectorProperties properties = new ElectorProperties();
        properties.setServiceName("elector-test");
        properties.setPoolSize(poolSize);
        properties.setHeartbeatIntervalMillis(heartbeatIntervalMillis);
        properties.setHeartbeatTimeoutMillis(heartbeatTimeoutMillis);
        properties.setBallotTimeoutMillis(ballotTimeoutMillis);
        properties.setBallotType(ballotType);
        return new TestEnvironment(newIp, properties);
      }
    }
  }

  private static class DiscoveryClientStub implements DiscoveryClient {

    private final List<ServiceInstance> instances = new ArrayList<>();

    public DiscoveryClientStub(ServiceInstance... instances) {
      addInstances(instances);
    }

    public void addInstances(List<ServiceInstance> instances) {
      this.instances.addAll(instances);
    }

    public void addInstances(ServiceInstance... instances) {
      addInstances(Arrays.asList(instances));
    }

    public void removeInstance(ServiceInstance instance) {
      this.instances.remove(instance);
    }

    @Override
    public String description() {
      return "Discovery client for Testing";
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceId) {
      return instances;
    }

    @Override
    public List<String> getServices() {
      return List.of("kubernetes", "elector-test");
    }
  }

  private static class EventDispatcher implements IntegrationFlow {

    private final SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("input-");

    private final Map<String, InstanceController> controllers = new HashMap<>();

    public void addPod(String ip, InstanceController controller) {
      controllers.put(ip, controller);
    }

    public void removePod(String ip) {
      controllers.remove(ip);
    }

    @Override
    public void configure(IntegrationFlowDefinition<?> flow) {}

    @Override
    public MessageChannel getInputChannel() {
      return (message, timeout) -> {
        ElectorEvent event = (ElectorEvent) message.getPayload();
        StringBuilder ip = new StringBuilder();
        try {
          URI destination = new URI((String) message.getHeaders().get(HEADER_TARGET));
          ip.append(destination.getHost());
        } catch (URISyntaxException e) {
          // Do nothing
        }
        executor.execute(
            () -> {
              // Simulate transport delay
              try {
                Thread.sleep((long) (Math.random() * 10));
              } catch (InterruptedException e) {
                // DO nothing
              }
              InstanceController pod = controllers.get(ip.toString());
              if (pod != null) {
                pod.handle(event, message.getHeaders());
              }
            });
        return true;
      };
    }

    public Map<String, InstanceController> getControllers() {
      return this.controllers;
    }
  }
}
