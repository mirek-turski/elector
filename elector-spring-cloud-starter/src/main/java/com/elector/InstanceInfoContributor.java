package com.elector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.info.Info.Builder;
import org.springframework.boot.actuate.info.InfoContributor;

import java.util.HashMap;
import java.util.Map;

public class InstanceInfoContributor implements InfoContributor {

  private static final Logger log = LoggerFactory.getLogger(InstanceInfoContributor.class);

  private final InstanceInfo selfInfo;
  private final InstanceController instanceController;

  public InstanceInfoContributor(
      final InstanceInfo selfInfo, final InstanceController instanceController) {
    this.selfInfo = selfInfo;
    this.instanceController = instanceController;
  }

  @Override
  public void contribute(Builder builder) {
    try {
      Map<String, Object> details = new HashMap<>();
      details.put("self", selfInfo);
      details.put("peers", instanceController.getPeers());
      builder.withDetail("instances", details);
    } catch (Exception e) {
      log.warn("Failed to produce instance info", e);
    }
  }
}
