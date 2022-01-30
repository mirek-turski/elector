package com.elector;

import static com.elector.Constant.HEADER_TARGET;
import static com.elector.Constant.ORDER_UNASSIGNED;
import static com.elector.Constant.STATE_NEW;

import com.elector.InstanceInfo.InstanceInfoBuilder;
import java.time.Instant;
import java.util.Random;
import javax.annotation.Nullable;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties;
import org.springframework.cloud.commons.util.InetUtils;
import org.springframework.cloud.kubernetes.PodUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(PodUtils.class)
  protected static class KubernetesConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public InstanceInfo selfInfo(@Nullable PodUtils podUtils, InstanceInfoBuilder builder) {
      if (podUtils != null && podUtils.isInsideKubernetes()) {
        builder
            .host(podUtils.currentPod().get().getStatus().getPodIP())
            .id(podUtils.currentPod().get().getMetadata().getUid());
      }
      return builder.build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  protected static class SimpleConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public InstanceInfo selfInfo(InstanceInfoBuilder builder, @Nullable SimpleDiscoveryProperties discoveryProperties) {
      final InstanceInfo selfInfo = builder.build();
      if (discoveryProperties != null && discoveryProperties.getLocal() != null) {
        discoveryProperties.getLocal().setInstanceId(selfInfo.getId());
      }
      return selfInfo;
    }
  }

  @Bean
  public InstanceInfoBuilder selfInfoBuilder(
      @Nullable InetUtils inet, ElectorProperties properties) {
    String hostname = "127.0.0.1";
    if (properties.getHostname() != null && !properties.getHostname().isBlank()) {
      hostname = properties.getHostname();
    } else if (inet != null) {
      hostname = inet.findFirstNonLoopbackHostInfo().getIpAddress();
    }
    return InstanceInfo.builder()
        .id(properties.getInstanceId())
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
    return f ->
        f.transform(Transformers.toJson())
            .handle(Udp.outboundAdapter(m -> m.getHeaders().get(HEADER_TARGET)));
  }

  @Bean
  public InstanceController instanceController(
      ElectorProperties properties,
      InstanceInfo selfInfo,
      DiscoveryClient discoveryClient,
      IntegrationFlow electorOutUdpAdapter,
      ApplicationEventPublisher eventPublisher) {
    return new InstanceController(
        properties, selfInfo, discoveryClient, electorOutUdpAdapter, eventPublisher);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(HealthIndicator.class)
  protected static class InstancesActuatorConfiguration {

    @Bean
    @ConditionalOnEnabledHealthIndicator("instances")
    public InstanceInfoContributor instanceInfoContributor(
        InstanceInfo selfInfo, InstanceController instanceController) {
      return new InstanceInfoContributor(selfInfo, instanceController);
    }
  }
}
