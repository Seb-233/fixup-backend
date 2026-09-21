package com.fixup.identityaccess.application;

import com.fixup.identityaccess.domain.UserAccount;

public record BootstrapResult(UserAccount user, boolean created) {
}
