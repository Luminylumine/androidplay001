package com.androidplay.mdclient.agent;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReplayFixtureOrderingTest {
    private static final String FIXTURE = "/fixtures/fourier-class.jsonl";

    @Test
    public void replaysFixtureResponsesInFileOrder() throws Exception {
        List<String> lines = fixtureLines();
        List<AgentResponse> responses = new ArrayList<AgentResponse>();
        for (String line : lines) {
            responses.add(new AgentResponse(field(line, "response"), null));
        }

        ReplayAgentBackend backend = new ReplayAgentBackend(responses);
        for (String line : lines) {
            assertEquals(field(line, "response"), backend.respond(new AgentRequest("", "", 3L)).getText());
        }
        assertEquals(lines.size(), backend.getReplayIndex());
    }

    @Test
    public void fixtureKeepsTranscriptAndActionHistoryOrderingVisible() throws Exception {
        List<String> types = new ArrayList<String>();
        boolean sawStale = false;
        boolean sawHistorical = false;
        for (String line : fixtureLines()) {
            types.add(field(line, "type"));
            sawStale |= line.contains("stale-action");
            sawHistorical |= line.contains("historical-action");
        }

        assertEquals(Arrays.asList("partial", "final", "page", "human_typo", "question", "stale_action", "historical_action"), types);
        assertTrue(sawStale);
        assertTrue(sawHistorical);
    }

    private List<String> fixtureLines() throws IOException {
        InputStream stream = getClass().getResourceAsStream(FIXTURE);
        if (stream == null) throw new IOException("Missing fixture: " + FIXTURE);
        List<String> lines = new ArrayList<String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) lines.add(line);
            }
        }
        return lines;
    }

    private String field(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) throw new IllegalArgumentException("Missing field " + name);
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) throw new IllegalArgumentException("Unterminated field " + name);
        return json.substring(start, end);
    }
}
