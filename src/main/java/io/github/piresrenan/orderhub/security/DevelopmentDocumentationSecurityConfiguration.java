package io.github.piresrenan.orderhub.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Only an explicit development runtime permits anonymous documentation; business chains remain authoritative. */
@Configuration(proxyBeanMethods = false)
@Profile("dev & !production & !prod & !staging & !pre-release")
public class DevelopmentDocumentationSecurityConfiguration {
    /** Matches documentation assets exclusively and never grants an application route additional access. */
    @Bean
    @Order(0)
    SecurityFilterChain developmentDocumentationFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/**")
                .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(login -> login.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll()).build();
    }
}
