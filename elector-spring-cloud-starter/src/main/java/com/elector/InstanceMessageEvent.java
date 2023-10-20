package com.elector;

import java.util.Map;
import org.springframework.context.ApplicationEvent;
import org.springframework.lang.NonNull;

/** Event sent on arrival of custom message from another peer. */
public class InstanceMessageEvent extends ApplicationEvent {

  private final InstanceInfo sender;
  private final String messageId;
  private final Map<String, String> properties;

  /**
   * Create a new ApplicationEvent.
   *
   * @param source The object on which the event initially occurred (never {@code null})
   * @param sender The instance that sent the message
   * @param messageId Id
   * @param properties Properties of the message
   */
  public InstanceMessageEvent(
      @NonNull Object source,
      @NonNull InstanceInfo sender,
      @NonNull String messageId,
      @NonNull Map<String, String> properties) {
    super(source);
    this.sender = sender;
    this.messageId = messageId;
    this.properties = properties;
  }

  public InstanceInfo getSender() {
    return this.sender;
  }

  public String getMessageId() {
    return this.messageId;
  }

  public Map<String, String> getProperties() {
    return this.properties;
  }

  public String toString() {
    return "InstanceMessageEvent(sender="
        + this.getSender()
        + ", messageId="
        + this.getMessageId()
        + ", properties="
        + this.getProperties()
        + ")";
  }
}
