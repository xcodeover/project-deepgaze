package com.deepgaze.session;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Typed outcome of an EXPLAIN call. Four shapes:
 *   OK        — plan is a valid JSON tree, render it.
 *   REJECTED  — we refused to run it (truncated / prepared / non-explainable /
 *               empty). Deterministic, no DB round-trip.
 *   FAILED    — engine threw (syntax, permissions, missing object). Message
 *               comes from the driver.
 *   UNSUPPORTED — target engine not wired up yet.
 *
 * The frontend switches on `status` and shows `plan` or `message` accordingly.
 */
public record ExplainResult(
        Status status,
        JsonNode plan,
        String message
) {
    public enum Status { OK, REJECTED, FAILED, UNSUPPORTED }

    public static ExplainResult ok(JsonNode plan) {
        return new ExplainResult(Status.OK, plan, null);
    }

    public static ExplainResult rejected(String reason) {
        return new ExplainResult(Status.REJECTED, null, reason);
    }

    public static ExplainResult failed(String reason) {
        return new ExplainResult(Status.FAILED, null, reason);
    }

    public static ExplainResult unsupported(String reason) {
        return new ExplainResult(Status.UNSUPPORTED, null, reason);
    }
}
