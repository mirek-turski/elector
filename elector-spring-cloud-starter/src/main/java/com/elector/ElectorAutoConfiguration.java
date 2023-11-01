package com.elector;

import static com.elector.Constant.HEADER_TARGET;
import static com.elector.Constant.ORDER_UNASSIGNED;
import static com.elector.Constant.STATE_NEW;

import com.elector.InstanceInfo.InstanceInfoBuilder;
import java.time.Instant;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.info.ConditionalOnEnabledInfoContributor;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryProperties;
import org.springframework.cloud.kubernetes.client.KubernetesClientPodUtils;
import org.springframework.cloud.kubernetes.fabric8.Fabric8PodUtils;
import org.springframework.cloud.zookeeper.discovery.ZookeeperDiscoveryProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.dsl.Transformers;
import org.springframework.integration.ip.dsl.Udp;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableDiscoveryClient
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ElectorProperties.class)
@ConditionalOnProperty(value = "spring.cloud.elector.enabled", matchIfMissing = true)
public class ElectorAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ElectorAutoConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty("spring.cloud.kubernetes.enabled")
  @ConditionalOnClass(name = "org.springframework.cloud.kubernetes.client.KubernetesClientPodUtils")
  protected static class KubernetesClientConfiguration {
    @Bean
    @Primary
    @ConditionalOnMissingBean
    @ConditionalOnProperty("spring.cloud.kubernetes.discovery.enabled")
    public InstanceInfo selfInfo(InstanceInfoBuilder builder, KubernetesClientPodUtils podUtils) {
      if (podUtils != null && podUtils.isInsideKubernetes()) {
        var id = Objects.requireNonNull(podUtils.currentPod().get().getMetadata()).getUid();
        var ip = Objects.requireNonNull(podUtils.currentPod().get().getStatus()).getPodIP();
        log.trace("Running inside Kubernetes, instance id={}, ip={}", id, ip);
        builder.id(id).host(ip);
      } else {
        log.trace("Not running inside Kubernetes");
      }
      return builder.build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty("spring.cloud.kubernetes.enabled")
  @ConditionalOnClass(name = "org.springframework.cloud.kubernetes.fabric8.Fabric8PodUtils")
  protected static class KubernetesFabric8Configuration {
    @Bean
    @Primary
    @ConditionalOnMissingBean
    @ConditionalOnProperty("spring.cloud.kubernetes.discovery.enabled")
    public InstanceInfo selfInfo(InstanceInfoBuilder builder, Fabric8PodUtils podUtils) {
      if (podUtils != null && podUtils.isInsideKubernetes()) {
        var id = Objects.requireNonNull(podUtils.currentPod().get().getMetadata()).getUid();
        var ip = Objects.requireNonNull(podUtils.currentPod().get().getStatus()).getPodIP();
        log.trace("Running inside Kubernetes, instance id={}, ip={}", id, ip);
        builder.id(id).host(ip);
      } else {
        log.trace("Not running inside Kubernetes");
      }
      return builder.build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty("spring.cloud.consul.enabled")
  @ConditionalOnClass(name = "org.springframework.cloud.consul.discovery.ConsulDiscoveryProperties")
  protected static class ConsulConfiguration {
    @Bean
    @Primary
    @ConditionalOnMissingBean
    @ConditionalOnProperty("spring.cloud.consul.discovery.enabled")
    public InstanceInfo selfInfo(InstanceInfoBuilder builder, ConsulDiscoveryProperties discoveryProperties) {
      if (discoveryProperties != null) {
        log.trace("Running with Consul, instance id={}, ip={}",
            discoveryProperties.getInstanceId(), discoveryProperties.getHostname());
        builder.id(discoveryProperties.getInstanceId()).host(discoveryProperties.getHostname());
      } else {
        log.trace("Not running with Consul");
      }
      return builder.build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty("spring.cloud.zookeeper.enabled")
  @ConditionalOnClass(name = "org.springframework.cloud.zookeeper.discovery.ZookeeperDiscoveryProperties")
  protected static class ZookeeperConfiguration {
    @Bean
    @Primary
    @ConditionalOnMissingBean
    @ConditionalOnProperty("spring.cloud.zookeeper.discovery.enabled")
    public InstanceInfo selfInfo(InstanceInfoBuilder builder, ZookeeperDiscoveryProperties discoveryProperties) {
      if (discoveryProperties != null) {
        log.trace("Running with Zookeeper, instance id={}, ip={}",
            discoveryProperties.getInstanceId(), discoveryProperties.getInstanceHost());
        builder.id(discoveryProperties.getInstanceId()).host(discoveryProperties.getInstanceHost());
      } else {
        log.trace("Not running with Zookeeper");
      }
      return builder.build();
    }
  }

  @Bean
  @ConditionalOnMissingBean
  public InstanceInfo selfInfo(InstanceInfoBuilder builder) {
    return builder.build();
  }

  /**
   * Provides convenient builder for customising InstanceInfo beyond the predefined configurations
   *
   * @param inet {@link InetUtils}
   * @param properties {@link ElectorProperties}
   * @return the builder
   */
  @Bean
  public InstanceInfoBuilder selfInfoBuilder(
      InetUtils inet, ElectorProperties properties) {
    String hostname = "127.0.0.1";
    if (properties.getHostname() != null && !properties.getHostname().isBlank()) {
      hostname = properties.getHostname();
    } else if (inet != null) {
      hostname = inet.findFirstNonLoopbackHostInfo().getIpAddress();
    }
    return InstanceInfo.builder()
        .id(properties.getInstanceId() != null ? properties.getInstanceId() : UUID.randomUUID().toString())
        .host(hostname)
        .weight(Math.abs(new Random(System.currentTimeMillis()).nextLong()))
        .order(ORDER_UNASSIGNED)
        .state(STATE_NEW)
        .last(Instant.now());
  }

  /**
   * Creates {@link IntegrationFlow} responsible for inbound UDP communication from other instances
   *
   * @param controller Injected {@link InstanceController} bean that will receive the communication
   * @param properties Injected {@link ElectorProperties} bean
   * @return The flow
   */
  @Bean
  public IntegrationFlow electorInUdpAdapter(
      final InstanceController controller, final ElectorProperties properties) {
    return IntegrationFlows.from(Udp.inboundAdapter(properties.getListenerPort()))
        .transform(Transformers.fromJson(ElectorEvent.class))
        .handle(ElectorEvent.class, controller)
        .get();
  }

  /**
   * Creates {@link IntegrationFlow} responsible for outbound UDP communication to other instances
   *
   * @return The flow
   */
  @Bean
  public IntegrationFlow electorOutUdpAdapter() {
    return f -> f.transform(Transformers.toJson()).handle(Udp.outboundAdapter(m -> m.getHeaders().get(HEADER_TARGET)));
  }

  @Bean
  public InstanceController instanceController(
      ElectorProperties properties,
      InstanceRegistry instanceRegistry,
      IntegrationFlow electorOutUdpAdapter,
      ApplicationEventPublisher eventPublisher) {
    return new InstanceController(properties, instanceRegistry, electorOutUdpAdapter, eventPublisher);
  }

  @Bean
  public InstanceRegistry instanceRegistry(
      ElectorProperties properties, InstanceInfo selfInfo, DiscoveryClient discoveryClient) {
    return new InstanceRegistry(properties, selfInfo, discoveryClient);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(InfoContributor.class)
  protected static class InstancesActuatorConfiguration {
    @Bean
    @ConditionalOnEnabledInfoContributor("instances")
    public InstanceInfoContributor instanceInfoContributor(
        InstanceRegistry instanceRegistry) {
      return new InstanceInfoContributor(instanceRegistry);
    }
  }
}
