package com.example.Ece.agent.guard;

/** 守门检查结果：放行、放行但改写，或拒绝。 */
public class GuardrailCheck {

    private final boolean allowed;
    private final String reason;
    private final String rewrittenAnswer;

    private GuardrailCheck(boolean allowed, String reason, String rewrittenAnswer) {
        this.allowed = allowed;
        this.reason = reason;
        this.rewrittenAnswer = rewrittenAnswer;
    }

    public static GuardrailCheck allow(String answer) {
        return new GuardrailCheck(true, null, answer);
    }

    public static GuardrailCheck rewrite(String answer) {
        return new GuardrailCheck(true, "REWRITTEN", answer);
    }

    public static GuardrailCheck reject(String reason, String answer) {
        return new GuardrailCheck(false, reason, answer);
    }

    public boolean isAllowed() { return allowed; }

    public String getReason() { return reason; }

    public String getRewrittenAnswer() { return rewrittenAnswer; }
}
