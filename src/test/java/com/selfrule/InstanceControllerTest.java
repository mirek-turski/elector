package com.selfrule;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import javax.validation.constraints.NotNull;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.selfrule.InstanceConstant.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@Slf4j
public class InstanceControllerTest {

  static {
    LoggerContext loggerContext = (LoggerContext)LoggerFactory.getILoggerFactory();
    loggerContext.getLogger("ROOT").setLevel(Level.ERROR);
    loggerContext.getLogger("com.selfrule").setLevel(Level.TRACE);
  }

  private final InstanceConfig config = new InstanceConfig("self-rule-test");
  final InstanceController controller = new InstanceController(config, null, null, null, null);

  InstanceInfo self =
      InstanceInfo.builder().weight(1233L).order(ORDER_UNASSIGNED).state(STATE_INTRODUCED).build();

  InstanceInfo intro =
      InstanceInfo.builder().weight(1234L).order(ORDER_UNASSIGNED).state(STATE_INTRODUCED).build();

  private static InstanceInfo getInfo(InstanceController controller) {
    return (InstanceInfo) ReflectionTestUtils.getField(controller, "selfInfo");
  }

  @Test
  public void testHighestOrderWithNoPeers() {
    log.info("==================== Running testHighestOrderWithNoPeers");
    DummyKubernetes k8s = DummyKubernetes.builder().build();
    String ip = k8s.addPod();
    k8s.startPods(ip);
    ArgumentCaptor<InstanceReadyEvent> argumentCaptor =
        ArgumentCaptor.forClass(InstanceReadyEvent.class);
    verify(k8s.getEventPublishers().get(ip), timeout(3000).times(1))
        .publishEvent(argumentCaptor.capture());
    InstanceInfo selfInfo = argumentCaptor.getValue().getSelfInfo();
    assertEquals(STATE_ACTIVE, selfInfo.getState());
    assertEquals(ORDER_HIGHEST, selfInfo.getOrder());
  }

  @Test
  public void testStartTwoInstancesSimultaneously() {
    log.info("==================== Running testStartTwoInstancesSimultaneously");
    DummyKubernetes k8s = DummyKubernetes.builder().poolSize(2).build();
    String ip1 = k8s.addPod();
    String ip2 = k8s.addPod();
    k8s.startPods(ip1, ip2);

    ArgumentCaptor<ApplicationEvent> eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
    verify(k8s.getEventPublishers().get(ip1), timeout(3000).times(1))
        .publishEvent(eventCaptor.capture());
    verify(k8s.getEventPublishers().get(ip2), timeout(3000).times(1))
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
    DummyKubernetes k8s = DummyKubernetes.builder().poolSize(2).build();
    String ip1 = k8s.addPod();
    String ip2 = k8s.addPod();
    k8s.startPods(ip1, ip2);
    String ip3 = k8s.addPod();
    k8s.startPods(ip3);
    ArgumentCaptor<ApplicationEvent> eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
    verify(k8s.getEventPublishers().get(ip3), timeout(3000).times(1)).publishEvent(eventCaptor.capture());
    assertTrue(eventCaptor.getValue() instanceof InstanceReadyEvent);
    assertTrue(getInfo(k8s.getController(ip3)).inState(STATE_SPARE));
  }

  @Test
  public void testDeletedInstanceReplacedWithSpare() {
    log.info("==================== Running testDeletedInstanceReplacedWithSpare");
    DummyKubernetes k8s = DummyKubernetes.builder().poolSize(2).build();
    log.info(">>>>>> Starting up two instances simultaneously");
    String ip1 = k8s.addPod();
    String ip2 = k8s.addPod();
    k8s.startPods(ip1, ip2);
    log.info(">>>>>> Starting spare instance");
    String ip3 = k8s.addPod();
    k8s.startPods(ip3);
    int orderToClaim = getInfo(k8s.getController(ip2)).getOrder();
    log.info(">>>>>> Removing second/active instance");
    k8s.deletePod(ip2);
    await()
        .atMost(config.getHeartbeatTimeoutMillis() * 2L, TimeUnit.MILLISECONDS)
        .until(() -> getInfo(k8s.getController(ip3)).inState(STATE_ACTIVE));
    ArgumentCaptor<ApplicationEvent> eventCaptor = ArgumentCaptor.forClass(ApplicationEvent.class);
    verify(k8s.getEventPublishers().get(ip3), timeout(3000).times(3))
        .publishEvent(eventCaptor.capture());
    List<ApplicationEvent> events = eventCaptor.getAllValues();
    assertTrue(events.get(0) instanceof InstanceReadyEvent);
    assertTrue(events.get(1) instanceof InstanceRemovedEvent);
    assertTrue(events.get(2) instanceof InstanceReadyEvent);
    assertEquals(ip2, ((InstanceRemovedEvent) events.get(1)).getInstanceInfo().getIp());
    InstanceInfo activatedInstance = ((InstanceReadyEvent) events.get(2)).getSelfInfo();
    assertTrue(activatedInstance.inState(STATE_ACTIVE));
    assertEquals(ip3, activatedInstance.getIp());
    assertEquals(orderToClaim, activatedInstance.getOrder());
  }

  @Test
  public void testAbsentInstance() {
    log.info("==================== Running testAbsentInstance");
    DummyKubernetes k8s = DummyKubernetes.builder().poolSize(2).build();
    log.info(">>>>>> Starting up two instances simultaneously");
    String ip1 = k8s.addPod();
    String ip2 = k8s.addPod();
    k8s.startPods(ip1, ip2);
    log.info(">>>>>> Starting spare instance");
    String ip3 = k8s.addPod();
    k8s.startPods(ip3);
    InstanceController controller1 = k8s.getController(ip1);
    InstanceController controller3 = k8s.getController(ip3);
    log.info(">>>>>> Muting instance #1");
    k8s.mutePod(ip1);
    await()
        .atMost(config.getHeartbeatTimeoutMillis(), TimeUnit.MILLISECONDS)
        .until(
            () -> controller3.getPeers().get(getInfo(controller1).getId()).inState(STATE_ABSENT));
    assertTrue(getInfo(controller3).inState(STATE_SPARE));
    log.info(">>>>>> Unmuting instance #1");
    k8s.unmutePod(ip1);
    await()
        .atMost(config.getHeartbeatTimeoutMillis(), TimeUnit.MILLISECONDS)
        .until(
            () -> controller3.getPeers().get(getInfo(controller1).getId()).inState(STATE_ACTIVE));
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
    config.setPoolSize(1);
    assertOrder(1, self, self, null, null);
  }

  @Test
  public void testResolveOrderSelfReadyPeerIntroduced() {
    config.setPoolSize(2);
    assertOrder(2, self.toBuilder().order(1).state(STATE_ACTIVE).build(), intro, null, null);
    assertOrder(1, self.toBuilder().order(2).state(STATE_ACTIVE).build(), intro, null, null);
  }

  @Test
  public void testResolveOrderSelfDiscoveredPeer() {
    config.setPoolSize(2);
    final InstanceInfo discoveredInstance =
        InstanceInfo.builder().weight(0).order(0).state(STATE_DISCOVERED).build();
    assertOrder(1, self, self, List.of(discoveredInstance), null);
  }

  @Test
  public void testResolveOrderSelfPeersReady() {
    config.setPoolSize(4);
    assertOrder(3, self, self, null, null, 1, 2, 4);
    assertOrder(2, self, self, null, null, 1, 3, 4);
    assertOrder(1, self, self, null, null, 2, 3, 4);
    assertOrder(4, self, self, null, null, 1, 2, 3);
    assertOrder(0, self, self, null, null, 1, 2, 3, 4);
  }

  @Test
  public void testResolveOrderWeighted() {
    config.setPoolSize(4);

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
    for (int i = 0; i < readyOrderValues.length; i++) {
      peers.put(
          UUID.randomUUID().toString(),
          InstanceInfo.builder()
              .weight((long) (Math.random() * 1000))
              .order(readyOrderValues[i])
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

  @Getter
  private static class DummyKubernetes {

    private final EventDispatcher eventDispatcher = new EventDispatcher();
    private final DiscoveryClientStub discoveryClient = new DiscoveryClientStub();
    private final Map<String, ServiceInstanceStub> serviceInstances = new HashMap<>();
    private final Map<String, ApplicationEventPublisher> eventPublishers = new HashMap<>();
    private final Map<String, ScheduledTaskRegistrar> taskRegistrars = new HashMap<>();
    private final InstanceConfig config;
    private int newIp;

    DummyKubernetes(int newIp, InstanceConfig config) {
      this.newIp = newIp;
      this.config = config;
    }

    public static DummyKubernetesBuilder builder() {
      return new DummyKubernetesBuilder();
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
              .name("shr-ofx-fix-adapter-" + uuid)
              .ip(ip)
              .namespace("test")
              .order(ORDER_UNASSIGNED)
              .state(STATE_NEW)
              .last(Instant.now())
              .build();

      final ServiceInstanceStub serviceInstance = new ServiceInstanceStub(uuid, ip);
      final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
      final ScheduledTaskRegistrar taskRegistrar = new ScheduledTaskRegistrar();

      final InstanceController instanceController =
          new InstanceController(
              config, selfInfo, discoveryClient, eventDispatcher, eventPublisher);
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
          .atMost(config.getHeartbeatTimeoutMillis(), TimeUnit.MILLISECONDS)
          .until(
              () ->
                  controllers.stream()
                      .allMatch(
                          controller -> {
                            InstanceInfo selfInfo = getInfo(controller);
                            return selfInfo.inState(STATE_ACTIVE) || selfInfo.inState(STATE_SPARE);
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

    public static class DummyKubernetesBuilder {
      private int newIp = 1;
      private int heartbeatIntervalMillis = 200;
      private int heartbeatTimeoutMillis = 500;
      private int poolSize = 1;

      DummyKubernetesBuilder() {}

      public DummyKubernetesBuilder newIp(int newIp) {
        this.newIp = newIp;
        return this;
      }

      public DummyKubernetesBuilder heartbeatIntervalMillis(int heartbeatIntervalMillis) {
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        return this;
      }

      public DummyKubernetesBuilder heartbeatTimeoutMillis(int heartbeatTimeoutMillis) {
        this.heartbeatTimeoutMillis = heartbeatTimeoutMillis;
        return this;
      }

      public DummyKubernetesBuilder poolSize(int poolSize) {
        this.poolSize = poolSize;
        return this;
      }

      public DummyKubernetes build() {
        InstanceConfig config = new InstanceConfig("shr-ofx-fix-adapter");
        config.setPoolSize(poolSize);
        config.setHeartbeatIntervalMillis(heartbeatIntervalMillis);
        config.setHeartbeatTimeoutMillis(heartbeatTimeoutMillis);
        return new DummyKubernetes(newIp, config);
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
      return List.of("kubernetes", "shr-ofx-fix-adapter");
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

    @Getter private final Map<String, InstanceController> controllers = new HashMap<>();

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
        InstanceEvent event = (InstanceEvent) message.getPayload();
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
  }
}
