package com.civicpulse.diff;

public final class NodeCapacityException extends RuntimeException {
    public NodeCapacityException(int limit) {
        super("Node capacity limit reached (" + limit + ")");
    }
}