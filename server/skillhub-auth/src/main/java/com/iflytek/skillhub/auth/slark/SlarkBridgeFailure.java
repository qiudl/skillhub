package com.iflytek.skillhub.auth.slark;

/** Only fixed, non-sensitive reason codes may cross the bridge HTTP boundary. */
public class SlarkBridgeFailure extends RuntimeException {
    private final int status;
    public SlarkBridgeFailure(int status, String code) { super(code); this.status = status; }
    public int status() { return status; }
    static SlarkBridgeFailure invalid() { return new SlarkBridgeFailure(401, "INVALID_GRANT"); }
    static SlarkBridgeFailure unavailable() { return new SlarkBridgeFailure(503, "SLARK_UNAVAILABLE"); }
}
