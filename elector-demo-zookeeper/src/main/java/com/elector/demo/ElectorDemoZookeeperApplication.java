package com.elector.demo;

import com.elector.InstanceInfo;
import com.elector.InstanceInfo.InstanceInfoBuilder;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.zookeeper.discovery.ZookeeperDiscoveryProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableDiscoveryClient
public class ElectorDemoZookeeperApplication {

  private static final Logger log = LoggerFactory.getLogger(ElectorDemoZookeeperApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(ElectorDemoZookeeperApplication.class, args);
  }

  @Bean
  public InstanceInfo selfInfo(
      InstanceInfoBuilder builder,
      @Nullable ZookeeperDiscoveryProperties zookeeperDiscoveryProperties) {
    if (zookeeperDiscoveryProperties != null) {
      builder.id(zookeeperDiscoveryProperties.getInstanceId());
      builder.host(zookeeperDiscoveryProperties.getInstanceHost());
    }
    return builder.build();
  }
}
