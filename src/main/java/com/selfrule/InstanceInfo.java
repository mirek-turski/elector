package com.selfrule;

import lombok.Builder;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.time.Instant;
import java.util.Arrays;

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
