package com.androidplay.mdclient.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContextAssemblerTest {
    @Test
    public void includesLayersInPriorityOrderWithinBothBudgets() {
        ContextAssembler assembler = new ContextAssembler(30, 10);

        ContextAssembler.Result result = assembler.assemble("system", "outline", "search", "around", "transcript", "document");

        assertEquals("system\noutline\nsearch\naround\ntrans", result.getText());
        assertEquals("system", result.getLayer(ContextLayer.L0));
        assertEquals("outline", result.getLayer(ContextLayer.L1));
        assertEquals("search", result.getLayer(ContextLayer.L2));
        assertEquals("around", result.getLayer(ContextLayer.L3));
        assertEquals("trans", result.getLayer(ContextLayer.L4));
        assertFalse(result.getLayers().containsKey(ContextLayer.L5));
        assertEquals(30, result.getChars());
        assertEquals(10, result.getTokens());
    }

    @Test
    public void truncatesTheFirstLayerThatExhaustsBudgetAndDropsLaterLayers() {
        ContextAssembler assembler = new ContextAssembler(9, 3);

        ContextAssembler.Result result = assembler.assemble("123456", "abcdef", "later", "", "", "");

        assertEquals("123456\nabc", result.getText());
        assertEquals("123456", result.getLayer(ContextLayer.L0));
        assertEquals("abc", result.getLayer(ContextLayer.L1));
        assertEquals(9, result.getChars());
        assertEquals(3, result.getTokens());
        assertTrue(result.getLayers().containsKey(ContextLayer.L0));
    }

    @Test
    public void providerOverloadBuildsOutlineSearchAndReadAroundLayers() {
        ContextAssembler assembler = new ContextAssembler(100, 100,
                () -> "outline", query -> "hit:" + query, (blockId, radius) -> "around:" + blockId);

        ContextAssembler.Result result = assembler.assemble("system", "why", "b-2", "notes", "doc");

        assertEquals("outline", result.getLayer(ContextLayer.L1));
        assertEquals("hit:why", result.getLayer(ContextLayer.L2));
        assertEquals("around:b-2", result.getLayer(ContextLayer.L3));
    }
}
