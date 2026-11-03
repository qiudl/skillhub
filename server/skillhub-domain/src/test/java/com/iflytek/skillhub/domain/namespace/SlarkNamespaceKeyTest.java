package com.iflytek.skillhub.domain.namespace;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SlarkNamespaceKeyTest {
    @Test void opaqueKeysAreStrictAndTheTupleIsUnambiguous() {
        for (String invalid : new String[]{"", " leading", "trailing ", "\u00a0edge", "edge\ufeff", "bad\n", "x".repeat(201), "\ud800"})
            assertThatThrownBy(() -> new SlarkNamespaceKey("registry", "project", invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlarkNamespaceKey("registry", "public", "other")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlarkNamespaceKey("registry", "unknown", "owner")).isInstanceOf(IllegalArgumentException.class);
        var key = new SlarkNamespaceKey("registry", "project", "用户/path: A");
        assertThat(key.namespaceSlug()).matches("slark-[0-9a-f]{58}");
        assertThat(key.namespaceSlug()).isEqualTo(new SlarkNamespaceKey("registry", "project", "用户/path: A").namespaceSlug());
        assertThat(key.namespaceSlug()).isNotEqualTo(new SlarkNamespaceKey("registry", "personal", "用户/path: A").namespaceSlug());
    }
}
