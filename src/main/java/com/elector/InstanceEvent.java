package com.elector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import javax.annotation.Nullable;
import javax.validation.constraints.NotBlank;
import java.io.Serializable;
import java.util.Map;

/** Contract for communication between instances of this microservice */
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

  InstanceEvent(
      @NotBlank String event,
      @NotBlank String id,
      @NotBlank String name,
      @NotBlank String ip,
      @NotBlank String namespace,
      @NotBlank String state,
      int order,
      long weight,
      @Nullable Map<String, String> properties) {
    this.event = event;
    this.id = id;
    this.name = name;
    this.ip = ip;
    this.namespace = namespace;
    this.state = state;
    this.order = order;
    this.weight = weight;
    this.properties = properties;
  }

  public static EventBuilder builder() {
    return new EventBuilder();
  }

  public @NotBlank String getEvent() {
    return this.event;
  }

  public @NotBlank String getId() {
    return this.id;
  }

  public @NotBlank String getName() {
    return this.name;
  }

  public @NotBlank String getIp() {
    return this.ip;
  }

  public @NotBlank String getNamespace() {
    return this.namespace;
  }

  public @NotBlank String getState() {
    return this.state;
  }

  public int getOrder() {
    return this.order;
  }

  public long getWeight() {
    return this.weight;
  }

  @Nullable
  public Map<String, String> getProperties() {
    return this.properties;
  }

  public String toString() {
    return "InstanceEvent(event="
        + this.getEvent()
        + ", id="
        + this.getId()
        + ", name="
        + this.getName()
        + ", ip="
        + this.getIp()
        + ", namespace="
        + this.getNamespace()
        + ", state="
        + this.getState()
        + ", order="
        + this.getOrder()
        + ", weight="
        + this.getWeight()
        + ", properties="
        + this.getProperties()
        + ")";
  }

  public EventBuilder toBuilder() {
    return new EventBuilder()
        .event(this.event)
        .id(this.id)
        .name(this.name)
        .ip(this.ip)
        .namespace(this.namespace)
        .state(this.state)
        .order(this.order)
        .weight(this.weight)
        .properties(this.properties);
  }

  /** Provides Jackson-aware builder */
  @JsonPOJOBuilder(withPrefix = "")
  public static class EventBuilder {
    private @NotBlank String event;
    private @NotBlank String id;
    private @NotBlank String name;
    private @NotBlank String ip;
    private @NotBlank String namespace;
    private @NotBlank String state;
    private int order;
    private long weight;
    private Map<String, String> properties;

    EventBuilder() {}

    public EventBuilder event(@NotBlank String event) {
      this.event = event;
      return this;
    }

    public EventBuilder id(@NotBlank String id) {
      this.id = id;
      return this;
    }

    public EventBuilder name(@NotBlank String name) {
      this.name = name;
      return this;
    }

    public EventBuilder ip(@NotBlank String ip) {
      this.ip = ip;
      return this;
    }

    public EventBuilder namespace(@NotBlank String namespace) {
      this.namespace = namespace;
      return this;
    }

    public EventBuilder state(@NotBlank String state) {
      this.state = state;
      return this;
    }

    public EventBuilder order(int order) {
      this.order = order;
      return this;
    }

    public EventBuilder weight(long weight) {
      this.weight = weight;
      return this;
    }

    public EventBuilder properties(Map<String, String> properties) {
      this.properties = properties;
      return this;
    }

    public InstanceEvent build() {
      return new InstanceEvent(event, id, name, ip, namespace, state, order, weight, properties);
    }
  }
}
