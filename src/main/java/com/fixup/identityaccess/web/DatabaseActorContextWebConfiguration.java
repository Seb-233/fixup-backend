package com.fixup.identityaccess.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class DatabaseActorContextWebConfiguration implements WebMvcConfigurer {
    private final CurrentActorProvider currentActorProvider;

    DatabaseActorContextWebConfiguration(CurrentActorProvider currentActorProvider) {
        this.currentActorProvider = currentActorProvider;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new DatabaseActorContextInterceptor(currentActorProvider)).excludePathPatterns("/auth/bootstrap");
    }
}