package com.stellartrack.order.domain;

/** ENTRY orders are subject to duplicate-entry prevention; HEDGE/CLOSE_OUT must link to an entry. */
public enum OrderPurpose {
    ENTRY,
    HEDGE,
    CLOSE_OUT
}
