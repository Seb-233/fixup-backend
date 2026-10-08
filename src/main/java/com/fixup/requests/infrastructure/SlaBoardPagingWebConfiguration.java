package com.fixup.requests.infrastructure;

import com.fixup.requests.application.SlaBoardPageable;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class SlaBoardPagingWebConfiguration implements WebMvcConfigurer {
    private final PageableHandlerMethodArgumentResolver pageableResolver;

    SlaBoardPagingWebConfiguration(PageableHandlerMethodArgumentResolver pageableResolver) {
        this.pageableResolver = pageableResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == SlaBoardPageable.class;
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                    NativeWebRequest request, WebDataBinderFactory binderFactory) {
                return new SlaBoardPageable(pageableResolver.resolveArgument(
                        parameter, container, request, binderFactory));
            }
        });
    }
}
