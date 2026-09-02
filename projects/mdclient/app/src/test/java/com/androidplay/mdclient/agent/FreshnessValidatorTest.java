package com.androidplay.mdclient.agent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FreshnessValidatorTest {
    private static final long CREATED = 10_000L;
    private final FreshnessValidator validator =
            new FreshnessValidator(new FreshnessPolicy(1_000L, 250L));

    @Test
    public void acceptsMatchingTargetRevision() {
        AgentAction action = action(7L, CREATED);

        assertTrue(validator.isFresh(action, CREATED + 500L, 7L));
        assertFalse(validator.isFresh(action, CREATED + 500L, 8L));
    }

    @Test
    public void expiresAtTtlBoundaryAndRejectsClockBeforeCreation() {
        AgentAction action = action(7L, CREATED);

        assertTrue(validator.isFresh(action, CREATED + 1_000L, 7L));
        assertFalse(validator.isFresh(action, CREATED + 1_001L, 7L));
        assertFalse(validator.isFresh(action, CREATED - 1L, 7L));
    }

    @Test
    public void enforcesMinimumActionInterval() {
        AgentAction action = action(7L, CREATED);

        assertFalse(validator.isFresh(action, CREATED + 500L, 7L, CREATED + 300L));
        assertTrue(validator.isFresh(action, CREATED + 550L, 7L, CREATED + 300L));
        assertTrue(validator.isFresh(action, CREATED + 500L, 7L, -1L));
    }

    private AgentAction action(long revision, long createdAtMs) {
        return new AgentAction(AgentAction.Type.SUGGEST, "fourier-02", "x", revision, createdAtMs);
    }
}
