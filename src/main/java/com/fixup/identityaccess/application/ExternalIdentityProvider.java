package com.fixup.identityaccess.application;

import com.fixup.identityaccess.domain.ExternalIdentity;

public interface ExternalIdentityProvider {
    ExternalIdentity currentIdentity();
    String currentSubject();
}
