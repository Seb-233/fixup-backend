package com.fixup.payments.api;

public class DebtNotFoundException extends RuntimeException {
    public DebtNotFoundException() {
        super("The debt does not exist");
    }
}
