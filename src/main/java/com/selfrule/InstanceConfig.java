package com.selfrule;

import io.fabric8.kubernetes.api.model.Pod;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.kubernetes.PodUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.dsl.Transformers;
import org.springframework.integration.ip.dsl.Udp;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

import static com.selfrule.InstanceConstant.*;

/**
 * Together with {@link InstanceController} provides self-governing capabilities, like leadership
 * election and responsibility management.
 */
@EnableDiscoveryClient
@EnableScheduling
@Configuration
@Slf4j
@Data
@ConfigurationProperties(prefix = "instance")
public class InstanceConfig {

  @NotBlank private String serviceName;

  @Min(1)
  @Max(65535)
  private int listenerPort = 12321;

  @Min(100)
  private int heartbeatIntervalMillis = 1000;

  @Min(100)
  private int heartbeatTimeoutMillis = 5000;

  private int poolSize = 0;

  /**
   * Create this configuration
   *
   * @param serviceName Service name in Kubernetes, defaults to application name
   */
  public InstanceConfig(@Value("${spring.application.name}") String serviceName) {
    this.serviceName = serviceName;
  }

  @Bean
  public InstanceInfo selfInfo(PodUtils podUtils) {
    InstanceInfo selfInfo;
    final Pod current = podUtils.currentPod().get();
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
              .name("localhost")
              .ip("127.0.0.1")
              .namespace(UNKNOWN)
              .order(ORDER_UNASSIGNED)
              .state(STATE_NEW)
              .last(Instant.now())
              .build();
    }
    log.info("This {}", selfInfo);
    return selfInfo;
  }

  /**
   * Creates {@link IntegrationFlow} responsible for inbound UDP communication from other instances
   *
   * @param controller {@link InstanceController} receiving the communication
   * @return The flow
   */
  @Bean
  public IntegrationFlow inUdpAdapter(final InstanceController controller) {
    return IntegrationFlows.from(Udp.inboundAdapter(listenerPort))
        .transform(Transformers.fromJson(InstanceEvent.class))
        .handle(InstanceEvent.class, controller)
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
}
