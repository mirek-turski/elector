package com.elector;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.info.Info.Builder;
import org.springframework.boot.actuate.info.InfoContributor;

public class InstanceInfoContributor implements InfoContributor {

  private static final Logger log = LoggerFactory.getLogger(InstanceInfoContributor.class);

  private final InstanceRegistry registry;

  public InstanceInfoContributor(final InstanceRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void contribute(Builder builder) {
    try {
      Map<String, Object> details = new HashMap<>();
      details.put("self", registry.getSelfInfo());
      details.put("peers", registry.getPeers());
      builder.withDetail("instances", details);
    } catch (Exception e) {
      log.warn("Failed to produce instance info", e);
    }
  }
}
