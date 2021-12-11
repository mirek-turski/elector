package com.elector;

import static com.elector.Constant.HEADER_TARGET;
import static com.elector.Constant.ORDER_UNASSIGNED;
import static com.elector.Constant.STATE_NEW;

import io.fabric8.kubernetes.api.model.Pod;
import java.time.Instant;
import java.util.UUID;
import javax.annotation.Nullable;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
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

  @Bean
  @ConditionalOnMissingBean
  public InstanceInfo selfInfo(@Nullable PodUtils podUtils, @Nullable InetUtils inet, ElectorProperties properties) {

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
              .host(current.getStatus().getPodIP())
              .order(ORDER_UNASSIGNED)
              .state(STATE_NEW)
              .last(Instant.now())
              .build();
    } else {
      String hostname =  "127.0.0.1";
      if (properties.getHostname() != null && !properties.getHostname().isBlank()) {
        hostname = properties.getHostname();
      } else if (inet != null) {
        hostname = inet.findFirstNonLoopbackHostInfo().getIpAddress();
      }
      selfInfo =
          InstanceInfo.builder()
              .id(id)
              .weight((long) (System.currentTimeMillis() * Math.random()))
              .name(properties.getServiceName())
              .host(hostname)
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
