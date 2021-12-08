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
import ch.qos.logback.classic.LoggerContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlowDefinition;
import org.springframework.messaging.MessageChannel;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.test.util.ReflectionTestUtils;

public class InstanceControllerTest {

  private static final Logger log = LoggerFactory.getLogger(InstanceControllerTest.class);

  static {
    LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    loggerContext.getLogger("ROOT").setLevel(Level.ERROR);
    loggerContext.getLogger("com.elector").setLevel(Level.DEBUG);
  }

  private final ElectorProperties properties = new ElectorProperties();
  final InstanceController controller = new InstanceController(properties, null, null, null, null);

  InstanceInfo self =
      InstanceInfo.builder().weight(1233L).order(ORDER_UNASSIGNED).state(STATE_INTRODUCED).build();

  InstanceInfo intro =
      InstanceInfo.builder().weight(1234L).order(ORDER_UNASSIGNED).state(STATE_INTRODUCED).build();

  private static InstanceInfo getInfo(InstanceController controller) {
    return (InstanceInfo) ReflectionTestUtils.getField(controller, "selfInfo");
  }

  @Test
  public void testLeaderElection() {
    log.info("==================== Running testLeaderElection");
    TestEnvironment env = TestEnvironment.builder().poolSize(1).build();
    String ip1 = env.addPod();
    String ip2 = env.addPod();
    String ip3 = env.addPod();
    log.info(">>>>>> Electing leader from amongst three candidates");
    env.startPods(ip1, ip2, ip3);
    Set<InstanceController> controllers =
        Set.of(env.getController(ip1), env.getController(ip2), env.getController(ip3));
    final Set<InstanceInfo> instances = new HashSet<>();
    await()
        .atMost(3000, TimeUnit.MILLISECONDS)
        .until(
            () ->
                controllers.stream()
                    .allMatch(
                        controller -> {
                          InstanceInfo instance =
                              (InstanceInfo) ReflectionTestUtils.getField(controller, "selfInfo");
                          instances.add(instance);
                          return instance.inEitherState(STATE_ACTIVE, STATE_SPARE);
                        }));
    assertEquals(3, instances.size());
    List<InstanceInfo> leaders =
        instances.stream().filter(InstanceInfo::isLeader).collect(Collectors.toList());
    List<InstanceInfo> minions =
        instances.stream().filter(InstanceInfo::isMinion).collect(Collectors.toList());
    assertEquals(1, leaders.size());
    assertEquals(2, minions.size());
    log.info(">>>>>> Removing leader and triggering re-election");
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
    TestEnvironment env = TestEnvironment.builder().build();
    String ip = env.addPod();
    env.startPods(ip);
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
    TestEnvironment env = TestEnvironment.builder().poolSize(2).build();
    String ip1 = env.addPod();
    String ip2 = env.addPod();
    env.startPods(ip1, ip2);

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

    List<InstanceInfo> instances = List.of(i0, i1);

    Optional<InstanceInfo> o1 =
        instances.stream().filter(instance -> instance.getOrder() == 1).findFirst();
    Optional<InstanceInfo> o2 =
        instances.stream().filter(instance -> instance.getOrder() == 2).findFirst();
    assertTrue(o1.isPresent());
    assertTrue(o2.isPresent());
    assertTrue(o1.get().getWeight() > o2.get().getWeight());
  }

  @Test
  public void testSpareInstanceOnPoolExhausted() {
    log.info("==================== Running testSpareInstanceOnPoolExhausted");
    TestEnvironment env = TestEnvironment.builder().poolSize(2).build();
    String ip1 = env.addPod();
    String ip2 = env.addPod();
    env.startPods(ip1, ip2);
    String ip3 = env.addPod();
    env.startPods(ip3);
    ArgumentCaptor<ApplicationEvent> eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
    verify(env.getEventPublishers().get(ip3), timeout(3000).times(1))
        .publishEvent(eventCaptor.capture());
    assertTrue(eventCaptor.getValue() instanceof InstanceReadyEvent);
    assertTrue(getInfo(env.getController(ip3)).inState(STATE_SPARE));
  }

  @Test
  public void testDeletedInstanceReplacedWithSpare() {
    log.info("==================== Running testDeletedInstanceReplacedWithSpare");
    TestEnvironment env = TestEnvironment.builder().poolSize(2).build();
    log.info(">>>>>> Starting up two instances simultaneously");
    String ip1 = env.addPod();
    String ip2 = env.addPod();
    env.startPods(ip1, ip2);
    log.info(">>>>>> Starting spare instance");
    String ip3 = env.addPod();
    env.startPods(ip3);
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
    TestEnvironment env = TestEnvironment.builder().poolSize(2).build();
    log.info(">>>>>> Starting up two instances simultaneously");
    String ip1 = env.addPod();
    String ip2 = env.addPod();
    env.startPods(ip1, ip2);
    log.info(">>>>>> Starting spare instance");
    String ip3 = env.addPod();
    env.startPods(ip3);
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
    assertOrder(2, self.toBuilder().order(1).state(STATE_ACTIVE).build(), intro, null, null);
    assertOrder(1, self.toBuilder().order(2).state(STATE_ACTIVE).build(), intro, null, null);
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

    assertOrder(0, self, self, null, List.of(intro.toBuilder().weight(1234L).build()), 1, 2, 4);
    assertOrder(3, self, self, null, List.of(intro.toBuilder().weight(1232L).build()), 1, 2, 4);
    assertOrder(0, self, self, null, List.of(intro.toBuilder().weight(1234L).build()), 1, 2, 3);
    assertOrder(4, self, self, null, List.of(intro.toBuilder().weight(1232L).build()), 1, 2, 3);
    assertOrder(0, self, self, null, List.of(intro.toBuilder().weight(1234L).build()), 2, 3, 4);
    assertOrder(1, self, self, null, List.of(intro.toBuilder().weight(1232L).build()), 2, 3, 4);

    assertOrder(3, self, intro.toBuilder().weight(1234L).build(), null, null, 1, 2, 4);
    assertOrder(0, self, intro.toBuilder().weight(1232L).build(), null, null, 1, 2, 4);
    assertOrder(4, self, intro.toBuilder().weight(1234L).build(), null, null, 1, 2, 3);
    assertOrder(0, self, intro.toBuilder().weight(1232L).build(), null, null, 1, 2, 3);
    assertOrder(1, self, intro.toBuilder().weight(1234L).build(), null, null, 2, 3, 4);
    assertOrder(0, self, intro.toBuilder().weight(1232L).build(), null, null, 2, 3, 4);
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

  private static class TestEnvironment {

    private final EventDispatcher eventDispatcher = new EventDispatcher();
    private final DiscoveryClientStub discoveryClient = new DiscoveryClientStub();
    private final Map<String, ServiceInstanceStub> serviceInstances = new HashMap<>();
    private final Map<String, ApplicationEventPublisher> eventPublishers = new HashMap<>();
    private final Map<String, ScheduledTaskRegistrar> taskRegistrars = new HashMap<>();
    private final ElectorProperties properties;
    private int newIp;

    TestEnvironment(int newIp, ElectorProperties properties) {
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
              .name("elector-test-" + uuid)
              .host(ip)
              .order(ORDER_UNASSIGNED)
              .state(STATE_NEW)
              .last(Instant.now())
              .build();

      final ServiceInstanceStub serviceInstance = new ServiceInstanceStub(uuid, ip);
      final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
      final ScheduledTaskRegistrar taskRegistrar = new ScheduledTaskRegistrar();

      final InstanceController instanceController =
          new InstanceController(
                  properties, selfInfo, discoveryClient, eventDispatcher, eventPublisher);
      serviceInstances.put(ip, serviceInstance);
      eventPublishers.put(ip, eventPublisher);
      taskRegistrars.put(ip, taskRegistrar);
      instanceController.configureTasks(taskRegistrar);
      eventDispatcher.addPod(ip, instanceController);

      return ip;
    }

    public void startPods(String... ips) {
      Arrays.stream(ips).forEach(ip -> discoveryClient.addInstances(serviceInstances.get(ip)));
      List<InstanceController> controllers = new ArrayList<>();
      Arrays.stream(ips)
          .forEach(
              ip -> {
                InstanceController controller = getController(ip);
                taskRegistrars.get(ip).afterPropertiesSet();
                controller.initialize();
                controllers.add(controller);
              });
      await()
          .atMost(properties.getHeartbeatTimeoutMillis() * 2L, TimeUnit.MILLISECONDS)
          .until(
              () ->
                  controllers.stream()
                      .allMatch(
                          controller -> {
                            InstanceInfo selfInfo = getInfo(controller);
                            return selfInfo.isActive() || selfInfo.isSpare();
                          }));
    }

    public void mutePod(String ip) {
      ReflectionTestUtils.setField(getController(ip), "outUdpAdapter", new EventDispatcher());
    }

    public void unmutePod(String ip) {
      ReflectionTestUtils.setField(getController(ip), "outUdpAdapter", eventDispatcher);
    }

    public void deletePod(String ip) {
      ReflectionTestUtils.setField(getController(ip), "discoveryClient", new DiscoveryClientStub());
      discoveryClient.removeInstance(serviceInstances.get(ip));
      eventDispatcher.removePod(ip);
      eventPublishers.remove(ip);
      serviceInstances.remove(ip);
    }

    public EventDispatcher getEventDispatcher() {
      return this.eventDispatcher;
    }

    public DiscoveryClientStub getDiscoveryClient() {
      return this.discoveryClient;
    }

    public Map<String, ServiceInstanceStub> getServiceInstances() {
      return this.serviceInstances;
    }

    public Map<String, ApplicationEventPublisher> getEventPublishers() {
      return this.eventPublishers;
    }

    public Map<String, ScheduledTaskRegistrar> getTaskRegistrars() {
      return this.taskRegistrars;
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

      public TestEnvironmentBuilder poolSize(int poolSize) {
        this.poolSize = poolSize;
        return this;
      }

      public TestEnvironment build() {
        ElectorProperties properties = new ElectorProperties();
        properties.setServiceName("elector-test");
        properties.setPoolSize(poolSize);
        properties.setHeartbeatIntervalMillis(heartbeatIntervalMillis);
        properties.setHeartbeatTimeoutMillis(heartbeatTimeoutMillis);
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

  private static class ServiceInstanceStub implements ServiceInstance {

    private final String instanceId;
    private final String host;

    public ServiceInstanceStub(String instanceId, String host) {
      this.instanceId = instanceId;
      this.host = host;
    }

    @Override
    public String getInstanceId() {
      return instanceId;
    }

    @Override
    public String getServiceId() {
      return null;
    }

    @Override
    public String getHost() {
      return host;
    }

    @Override
    public int getPort() {
      return 0;
    }

    @Override
    public boolean isSecure() {
      return false;
    }

    @Override
    public URI getUri() {
      return null;
    }

    @Override
    public Map<String, String> getMetadata() {
      return null;
    }
  }

  private static class EventDispatcher implements IntegrationFlow {

    private final SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();

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
