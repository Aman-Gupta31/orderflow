package com.aman.orderflow.common.idempotency;

public record ReplayedResponse(int status, String body) {
}
