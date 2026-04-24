package com.deepgaze.alert;

/**
 * Three-state model borrowed from Prometheus: a rule is OK by default, goes
 * PENDING the first time its predicate is true, and only transitions to FIRING
 * after the predicate has stayed true for the rule's `for` duration. This is
 * what keeps a single noisy tick from paging an operator.
 */
public enum AlertStatus { OK, PENDING, FIRING }
