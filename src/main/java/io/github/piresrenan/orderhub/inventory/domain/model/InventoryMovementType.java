package io.github.piresrenan.orderhub.inventory.domain.model;

/** Distinguishes legitimate stock intake from an explicit physical correction. */
public enum InventoryMovementType { RECEIPT, ADJUSTMENT }
