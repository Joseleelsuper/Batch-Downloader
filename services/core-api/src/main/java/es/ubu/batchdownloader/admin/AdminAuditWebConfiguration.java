package es.ubu.batchdownloader.admin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** La dependencia se resuelve únicamente para un fallo administrativo con actor identificado. */
@Configuration(proxyBeanMethods = false)
class AdminAuditWebConfiguration implements WebMvcConfigurer {
    private final ObjectProvider<AdminAuditService> audit;

    AdminAuditWebConfiguration(ObjectProvider<AdminAuditService> audit) { this.audit = audit; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminFailureAuditInterceptor(audit::getObject)).addPathPatterns("/api/v1/admin/**");
    }
}
