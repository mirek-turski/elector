package com.selfrule;

import lombok.Getter;
import lombok.ToString;
import org.springframework.context.ApplicationEvent;

import javax.validation.constraints.NotNull;
import java.util.Map;

/** Event sent on arrival of custom message from another peer. */
@Getter
@ToString
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
      @NotNull Object source,
      @NotNull InstanceInfo sender,
      @NotNull String messageId,
      @NotNull Map<String, String> properties) {
    super(source);
    this.sender = sender;
    this.messageId = messageId;
    this.properties = properties;
  }
}
