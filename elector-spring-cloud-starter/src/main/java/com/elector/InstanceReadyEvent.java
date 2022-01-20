package com.elector;

import org.springframework.context.ApplicationEvent;

/**
 * Event sent when this instance of the service is ready to proceed after it agreed its status with
 * other instances.
 */
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

  public InstanceInfo getSelfInfo() {
    return this.selfInfo;
  }

  public String toString() {
    return "InstanceReadyEvent(selfInfo=" + this.getSelfInfo() + ")";
  }
}
