package com.selfrule;

public enum SelfRuleMode {
  ELECTED_LEADER_INSTANCE,

  /**
   * New instances will have order number assigned from 1 to poolSize. When there are already enough
   * instances that fill up the pool, every new instance will be marked as spare. Removal of an
   * assigned pool instance will result in nomination of one of the spare ones. If there are no spare
   * instances to nominate, the missing pool slot will remain unassigned until a new instance
   * appears.
   */
  ORDERED_POOL_OF_INSTANCES,

  ORDERED_INSTANCES,
  FIXED_ORDERED_INSTANCES
}
