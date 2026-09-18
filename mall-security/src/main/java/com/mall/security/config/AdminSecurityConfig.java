package com.mall.security.config;

import com.mall.security.filter.AdminAuthFilter;
import com.mall.security.utils.MerchantJwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 平台管理端独立安全过滤器链，只作用于 {@code /api/admin/**}。
 *
 * <p>与商家链同样的道理：{@code SecurityConfig} 无 {@code @Order} 取
 * {@code LOWEST_PRECEDENCE}，本链 {@code @Order(2)} 先匹配，
 * {@code /api/admin/**} 完全不经过 C 端认证，其余路径也不经过本链。</p>
 */
@Configuration
@EnableWebSecurity
public class AdminSecurityConfig {

    @Bean
    @Order(2)
    public SecurityFilterChain adminFilterChain(HttpSecurity http, MerchantJwtUtil merchantJwtUtil)
            throws Exception {
        http
            .securityMatcher("/api/admin/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/admin/login").permitAll()
                .anyRequest().hasRole("SUPER_ADMIN")
            )
            .addFilterBefore(new AdminAuthFilter(merchantJwtUtil),
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
