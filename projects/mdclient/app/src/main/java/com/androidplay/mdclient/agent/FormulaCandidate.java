package com.androidplay.mdclient.agent;

public final class FormulaCandidate {
    private final String expression; private final String source; private final double confidence;
    public FormulaCandidate(String expression, String source, double confidence) { this.expression = expression; this.source = source; this.confidence = confidence; }
    public String getExpression() { return expression; } public String getSource() { return source; } public double getConfidence() { return confidence; }
}
