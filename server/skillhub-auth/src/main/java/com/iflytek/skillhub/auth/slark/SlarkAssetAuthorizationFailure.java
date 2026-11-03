package com.iflytek.skillhub.auth.slark;

import com.iflytek.skillhub.domain.shared.exception.LocalizedDomainException;

/** Fixed localized status; never transports remote response bodies or credentials. */
public final class SlarkAssetAuthorizationFailure extends LocalizedDomainException {
    private final int status;
    private SlarkAssetAuthorizationFailure(int status, String key) { super(key); this.status = status; }
    static SlarkAssetAuthorizationFailure invalid() { return new SlarkAssetAuthorizationFailure(401, "error.slark.session.invalid"); }
    static SlarkAssetAuthorizationFailure denied() { return new SlarkAssetAuthorizationFailure(403, "error.slark.asset.denied"); }
    static SlarkAssetAuthorizationFailure unavailable() { return new SlarkAssetAuthorizationFailure(503, "error.slark.authority.unavailable"); }
    @Override public int statusCode() { return status; }
}
