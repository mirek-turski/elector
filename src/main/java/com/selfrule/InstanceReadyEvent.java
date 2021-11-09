package com.selfrule;

import lombok.Getter;
import lombok.ToString;
import org.springframework.context.ApplicationEvent;

/**
 * Event sent when this instance of the service is ready to proceed after it agreed its status with
 * other instances.
 */
@Getter
@ToString
public class InstanceReadyEvent extends ApplicationEvent {

  private final InstanceInfo selfInfo;

  /**
   * Create a new ApplicationEvent.
   *
   * @param source the object on which the event initially occurred (never {@code null})
   * @param selfInfo Information about this instance
   */
  public InstanceReadyEvent(Object source, InstanceInfo selfInfo) {
    super(source);
    this.selfInfo = selfInfo;
  }
}
