package com.elector;

import io.fabric8.kubernetes.api.model.Pod;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.kubernetes.PodUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.dsl.Transformers;
import org.springframework.integration.ip.dsl.Udp;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.UUID;

import static com.elector.Constant.*;

@EnableScheduling
@EnableDiscoveryClient
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ElectorProperties.class)
@ConditionalOnProperty(value = "spring.cloud.elector.enabled", matchIfMissing = true)
public class ElectorAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public InstanceInfo selfInfo(@Nullable PodUtils podUtils, ElectorProperties properties) {
    InstanceInfo selfInfo;
    final Pod current = podUtils != null ? podUtils.currentPod().get() : null;
    final String id =
        current != null ? current.getMetadata().getUid() : UUID.randomUUID().toString();
    final long weight = (long) (System.currentTimeMillis() * Math.random());

    if (current != null) {
      selfInfo =
          InstanceInfo.builder()
              .id(id)
              .weight(weight)
              .name(current.getMetadata().getName())
              .ip(current.getStatus().getPodIP())
              .namespace(current.getMetadata().getNamespace())
              .order(ORDER_UNASSIGNED)
              .state(STATE_NEW)
              .last(Instant.now())
              .build();
    } else {
      selfInfo =
          InstanceInfo.builder()
              .id(id)
              .weight((long) (System.currentTimeMillis() * Math.random()))
              .name(properties.getServiceName())
              .ip(properties.getHostname())
              .namespace(UNKNOWN)
              .order(ORDER_UNASSIGNED)
              .state(STATE_NEW)
              .last(Instant.now())
              .build();
    }
    return selfInfo;
  }

  /**
   * Creates {@link IntegrationFlow} responsible for inbound UDP communication from other instances
   *
   * @param controller Injected {@link InstanceController} bean that will receive the communication
   * @param properties Injected {@link ElectorProperties} bean
   * @return The flow
   */
  @Bean
  public IntegrationFlow inUdpAdapter(
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
  public IntegrationFlow outUdpAdapter() {
    return f ->
        f.transform(Transformers.toJson())
            .handle(Udp.outboundAdapter(m -> m.getHeaders().get(HEADER_TARGET)));
  }

  @Bean
  public InstanceController instanceController(
      ElectorProperties properties,
      InstanceInfo selfInfo,
      DiscoveryClient discoveryClient,
      IntegrationFlow outUdpAdapter,
      ApplicationEventPublisher eventPublisher) {
    return new InstanceController(
        properties, selfInfo, discoveryClient, outUdpAdapter, eventPublisher);
  }

  @Bean
  @ConditionalOnClass(InfoContributor.class)
  public InstanceInfoContributor instanceInfoContributor(
      InstanceInfo selfInfo, InstanceController instanceController) {
    return new InstanceInfoContributor(selfInfo, instanceController);
  }
}
