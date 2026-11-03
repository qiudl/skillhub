package com.iflytek.skillhub.service;

import com.iflytek.skillhub.auth.slark.SlarkNamespaceProvisionSettings;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceBinding;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceBindingRepository;
import com.iflytek.skillhub.domain.namespace.SlarkNamespaceKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Coordinates a trusted provision request; no membership projection or publication activation. */
@Service
@ConditionalOnProperty(prefix = "skillhub.auth.slark", name = {"enabled", "provisioning-enabled"}, havingValue = "true")
public class SlarkNamespaceProvisionService {
    private final SlarkNamespaceBindingRepository repository;
    private final SlarkNamespaceProvisionSettings settings;
    public SlarkNamespaceProvisionService(SlarkNamespaceBindingRepository repository, SlarkNamespaceProvisionSettings settings) {
        this.repository = repository; this.settings = settings;
    }
    public SlarkNamespaceBinding provision(String kind, String ownerId) {
        return repository.provision(new SlarkNamespaceKey(settings.registryId(), kind, ownerId), settings.clientId());
    }
}
