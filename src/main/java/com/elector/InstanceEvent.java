package com.elector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Data;

import javax.annotation.Nullable;
import javax.validation.constraints.NotBlank;
import java.io.Serializable;
import java.util.Map;

/** Contract for communication between instances of this microservice */
@Data
@Builder(builderClassName = "EventBuilder", toBuilder = true)
@JsonDeserialize(builder = InstanceEvent.EventBuilder.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InstanceEvent implements Serializable {
  @NotBlank private final String event;
  @NotBlank private final String id;
  @NotBlank private final String name;
  @NotBlank private final String ip;
  @NotBlank private final String namespace;
  @NotBlank private final String state;
  private final int order;
  private final long weight;
  @Nullable private final Map<String, String> properties;

  /** Provides Jackson-aware builder */
  @JsonPOJOBuilder(withPrefix = "")
  public static class EventBuilder {}
}
