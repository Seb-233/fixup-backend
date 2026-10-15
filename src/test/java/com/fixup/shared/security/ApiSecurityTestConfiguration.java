package com.fixup.shared.security;

import com.fixup.integration.TestJwtConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** Lets MVC slices exercise the production security chain with the existing test JWT decoder. */
@TestConfiguration(proxyBeanMethods = false)
@Import({SecurityConfiguration.class, RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class, TestJwtConfiguration.class})
public class ApiSecurityTestConfiguration {}
