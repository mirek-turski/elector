package com.elector;

import static com.elector.Constant.ORDER_HIGHEST;
import static com.elector.Constant.ORDER_UNASSIGNED;
import static com.elector.Constant.STATE_ACTIVE;
import static com.elector.Constant.STATE_SPARE;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.io.Serializable;
import java.time.Instant;
import java.util.Arrays;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

/** Holds service instance information */
public class InstanceInfo implements Serializable {
  private final String id;
  private final String host;
  private String state;
  private int order;
  private final long weight;
  private transient Instant last;

  InstanceInfo(
      String id,
      String host,
      String state,
      int order,
      long weight,
      Instant last) {
    this.id = id;
    this.host = host;
    this.state = state;
    this.order = order;
    this.weight = weight;
    this.last = last;
  }

  public static InstanceInfoBuilder builder() {
    return new InstanceInfoBuilder();
  }

  @JsonIgnore
  public boolean isActive() {
    return inState(STATE_ACTIVE);
  }

  @JsonIgnore
  public boolean isNotActive() {
    return !inState(STATE_ACTIVE);
  }

  @JsonIgnore
  public boolean isAssigned() {
    return order > ORDER_UNASSIGNED;
  }

  @JsonIgnore
  public boolean isSpare() {
    return inState(STATE_SPARE);
  }

  @JsonIgnore
  public boolean isLeader() {
    return order == ORDER_HIGHEST;
  }

  @JsonIgnore
  public boolean isMinion() {
    return inState(STATE_SPARE);
  }

  public boolean inState(@NotNull String checkedState) {
    return checkedState.equals(this.state);
  }

  public boolean inEitherState(@NotEmpty String... states) {
    return Arrays.stream(states).anyMatch(checkedState -> checkedState.equals(this.state));
  }

  public boolean inNeitherState(@NotEmpty String... states) {
    return !inEitherState(states);
  }

  public String getId() {
    return this.id;
  }

  public String getHost() {
    return this.host;
  }

  public String getState() {
    return this.state;
  }

  public int getOrder() {
    return this.order;
  }

  public long getWeight() {
    return this.weight;
  }

  public Instant getLast() {
    return this.last;
  }

  public void setState(String state) {
    this.state = state;
  }

  public void setOrder(int order) {
    this.order = order;
  }

  public void setLast(Instant last) {
    this.last = last;
  }

  public String toString() {
    return "InstanceInfo(id="
        + this.getId()
        + ", host="
        + this.getHost()
        + ", state="
        + this.getState()
        + ", order="
        + this.getOrder()
        + ", weight="
        + this.getWeight()
        + ", last="
        + this.getLast()
        + ")";
  }

  public InstanceInfoBuilder toBuilder() {
    return new InstanceInfoBuilder()
        .id(this.id)
        .host(this.host)
        .state(this.state)
        .order(this.order)
        .weight(this.weight)
        .last(this.last);
  }

  public static class InstanceInfoBuilder {
    private String id;
    private String host;
    private String state;
    private int order;
    private long weight;
    private Instant last;

    InstanceInfoBuilder() {}

    public InstanceInfoBuilder id(String id) {
      this.id = id;
      return this;
    }

    public InstanceInfoBuilder host(String host) {
      this.host = host;
      return this;
    }

    public InstanceInfoBuilder state(String state) {
      this.state = state;
      return this;
    }

    public InstanceInfoBuilder order(int order) {
      this.order = order;
      return this;
    }

    public InstanceInfoBuilder weight(long weight) {
      this.weight = weight;
      return this;
    }

    public InstanceInfoBuilder last(Instant last) {
      this.last = last;
      return this;
    }

    public InstanceInfo build() {
      return new InstanceInfo(id, host, state, order, weight, last);
    }

  }
}
