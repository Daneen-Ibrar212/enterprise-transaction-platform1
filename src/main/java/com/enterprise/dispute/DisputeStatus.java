package com.enterprise.dispute;

public enum DisputeStatus {
    OPENED,             // newly received
    UNDER_REVIEW,       // merchant/admin is investigating
    EVIDENCE_SUBMITTED, // evidence sent to network
    WON,                // we won the chargeback
    LOST,               // customer won
    ACCEPTED;           // we accepted the dispute (didn't fight)

    public boolean isTerminal() {
        return this == WON || this == LOST || this == ACCEPTED;
    }

    public boolean isOpen() {
        return this == OPENED || this == UNDER_REVIEW || this == EVIDENCE_SUBMITTED;
    }
}