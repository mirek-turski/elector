package com.elector;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.time.Instant;
import java.util.Arrays;

import static com.elector.InstanceConstant.*;

/** Holds service instance information */
@Data
@Builder(toBuilder = true)
public class InstanceInfo implements Serializable {
  private final String id;
  private final String name;
  private final String ip;
  private final String namespace;
  private String state;
  private int order;
  private final long weight;
  private transient Instant last;

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
}
