package com.elector;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "spring.cloud.elector")
public class ElectorProperties {

  /**
   * Decides how the ballot is performed based on the number of votes received (equal to the number of live instances),
   * number of discovered instances, the poolSize of instances and the ballotTimeoutMillis.
   */
  public enum BallotType {
    /**
     * Vote held for a period of time equal to ballotTimeoutMillis.
     * Only those votes that arrived by the time are considered when deciding instance order.
     */
    TIMED,

    /**
     * Ballot held for as long as at least the number of the votes received equals the poolSize.
     * If the quorum is not reached, the voting will be repeated util it is.
     * Note that the number of live instances is lower than the poolSize this voting type will
     * result in the instance requesting the vote not being assigned an order for as long as the quorum is not reached.
     */
    QUORUM,

    /**
     * Vote held until all the discovered instances reply to call for vote.
     */
    UNANIMOUS
  }

  boolean enabled = true;

  @NotBlank
  @Value("${spring.application.name}")
  private String serviceName;

  @Min(1)
  @Max(65535)
  private int listenerPort = 12321;

  private String hostname;

  @Min(100)
  private int heartbeatIntervalMillis = 1000;

  @Min(100)
  private int heartbeatTimeoutMillis = 3000;

  @Positive
  private int ballotTimeoutMillis = 1000;

  @Positive
  private int poolSize = 1;

  @NotNull
  private BallotType ballotType = BallotType.QUORUM;

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

  public @Positive int getBallotTimeoutMillis() {
    return this.ballotTimeoutMillis;
  }

  public @Positive int getPoolSize() {
    return this.poolSize;
  }

  public @NotNull BallotType getBallotType() {
    return this.ballotType;
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

  public void setBallotTimeoutMillis(@Positive int ballotTimeoutMillis) {
    this.ballotTimeoutMillis = ballotTimeoutMillis;
  }

  public void setPoolSize(@Positive int poolSize) {
    this.poolSize = poolSize;
  }

  public void setBallotType(@NotNull BallotType ballotType) {
    this.ballotType = ballotType;
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
        + ", ballotTimeoutMillis="
        + this.getBallotTimeoutMillis()
        + ", poolSize="
        + this.getPoolSize()
        + ", ballotType="
        + this.getBallotType()
        + ")";
  }
}
