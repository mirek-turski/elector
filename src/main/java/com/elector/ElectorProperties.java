package com.elector;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "spring.cloud.elector")
public class ElectorProperties {

  boolean enabled = true;

  @NotBlank
  @Value("${spring.application.name:unknown}")
  private String serviceName;

  @Min(1)
  @Max(65535)
  private int listenerPort = 12321;

  private String hostname;

  @Min(100)
  private int heartbeatIntervalMillis = 1000;

  @Min(100)
  private int heartbeatTimeoutMillis = 2000;

  @Positive
  private int poolSize = 1;

  public boolean isEnabled() {
    return this.enabled;
  }

  public @NotBlank String getServiceName() {
    return this.serviceName;
  }

  public @Min(1) @Max(65535) int getListenerPort() {
    return this.listenerPort;
  }

  public String getHostname() {
    return this.hostname;
  }

  public @Min(100) int getHeartbeatIntervalMillis() {
    return this.heartbeatIntervalMillis;
  }

  public @Min(100) int getHeartbeatTimeoutMillis() {
    return this.heartbeatTimeoutMillis;
  }

  public @Positive int getPoolSize() {
    return this.poolSize;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public void setServiceName(@NotBlank String serviceName) {
    this.serviceName = serviceName;
  }

  public void setListenerPort(@Min(1) @Max(65535) int listenerPort) {
    this.listenerPort = listenerPort;
  }

  public void setHostname(String hostname) {
    this.hostname = hostname;
  }

  public void setHeartbeatIntervalMillis(@Min(100) int heartbeatIntervalMillis) {
    this.heartbeatIntervalMillis = heartbeatIntervalMillis;
  }

  public void setHeartbeatTimeoutMillis(@Min(100) int heartbeatTimeoutMillis) {
    this.heartbeatTimeoutMillis = heartbeatTimeoutMillis;
  }

  public void setPoolSize(@Positive int poolSize) {
    this.poolSize = poolSize;
  }

  public String toString() {
    return "ElectorProperties(enabled="
        + this.isEnabled()
        + ", serviceName="
        + this.getServiceName()
        + ", listenerPort="
        + this.getListenerPort()
        + ", hostname="
        + this.getHostname()
        + ", heartbeatIntervalMillis="
        + this.getHeartbeatIntervalMillis()
        + ", heartbeatTimeoutMillis="
        + this.getHeartbeatTimeoutMillis()
        + ", poolSize="
        + this.getPoolSize()
        + ")";
  }
}
