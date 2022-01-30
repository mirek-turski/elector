package com.elector.demo;

import com.elector.InstanceInfo;
import com.elector.InstanceInfo.InstanceInfoBuilder;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ElectorDemoConsulApplication {

  private static final Logger log = LoggerFactory.getLogger(ElectorDemoConsulApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(ElectorDemoConsulApplication.class, args);
  }

  @Bean
  public InstanceInfo selfInfo(
      InstanceInfoBuilder builder,
      @Nullable ConsulDiscoveryProperties discoveryProperties,
      DiscoveryClient discoveryClient) {
    if (discoveryProperties != null) {
      builder.id(discoveryProperties.getInstanceId());
//      var instances = discoveryClient.getInstances(discoveryProperties.getInstanceId());
//      log.debug("Initially discovered instances: {}", instances);
//      instances.stream()
//          .filter(
//              serviceInstance ->
//                  serviceInstance.getInstanceId().equals(discoveryProperties.getInstanceId()))
//          .findFirst()
//          .ifPresent(serviceInstance -> {
//            log.debug("Discovered self, host={}", serviceInstance.getHost());
//            builder.host(serviceInstance.getHost());});
    }
    return builder.build();
  }
}
