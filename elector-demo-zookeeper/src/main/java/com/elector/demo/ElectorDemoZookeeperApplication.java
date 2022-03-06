package com.elector.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class ElectorDemoZookeeperApplication {

  private static final Logger log = LoggerFactory.getLogger(ElectorDemoZookeeperApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(ElectorDemoZookeeperApplication.class, args);
  }

//  @Bean
//  public InstanceInfo selfInfo(
//      InstanceInfoBuilder builder,
//      @Nullable ConsulDiscoveryProperties discoveryProperties) {
//    if (discoveryProperties != null) {
//      builder.id(discoveryProperties.getInstanceId());
//      builder.host(discoveryProperties.getHostname());
//    }
//    return builder.build();
//  }
}
