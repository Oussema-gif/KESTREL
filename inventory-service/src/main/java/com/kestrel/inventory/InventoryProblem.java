package com.kestrel.inventory;

import com.kestrel.inventory.soap.InventoryFaultCode;

final class InventoryProblem extends RuntimeException {
    final InventoryFaultCode code;
    final Integer availableQuantity;

    InventoryProblem(InventoryFaultCode code, String message) {
        this(code, message, null);
    }

    InventoryProblem(InventoryFaultCode code, String message, Integer availableQuantity) {
        super(message);
        this.code = code;
        this.availableQuantity = availableQuantity;
    }
}
