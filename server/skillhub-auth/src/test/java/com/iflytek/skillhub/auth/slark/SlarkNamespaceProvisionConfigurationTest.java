package com.iflytek.skillhub.auth.slark;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class SlarkNamespaceProvisionConfigurationTest {
    @Test void bothSwitchesAreRequiredAndDisabledModeRequiresNoProvisioningSecrets() {
        for (String[] values : new String[][]{{}, {"skillhub.auth.slark.enabled=true"},
                {"skillhub.auth.slark.provisioning-enabled=true"},
                {"skillhub.auth.slark.enabled=true", "skillhub.auth.slark.provisioning-enabled=false"}})
            new ApplicationContextRunner().withUserConfiguration(SlarkNamespaceProvisionConfiguration.class)
                    .withPropertyValues(values).run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(SlarkNamespaceProvisionSettings.class);
                    });
    }
    @Test void writeCredentialCannotBeReusedFromEitherIdentityDirection() {
        var bridge = new SlarkBridgeSettings("https://slark.example", "https://skills.example", "slark", "s".repeat(40), "c".repeat(40));
        for (String token : new String[]{"", "s".repeat(40), "c".repeat(40), "x".repeat(257)})
            assertThatThrownBy(() -> SlarkNamespaceProvisionSettings.from(bridge, "registry", token)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SlarkNamespaceProvisionSettings.from(bridge, " registry", "p".repeat(40))).isInstanceOf(IllegalArgumentException.class);
        assertThat(SlarkNamespaceProvisionSettings.from(bridge, "registry", "p".repeat(40)).toString()).doesNotContain("p".repeat(40));
    }
}
