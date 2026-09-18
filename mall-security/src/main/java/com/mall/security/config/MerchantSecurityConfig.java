package com.mall.security.config;

import com.mall.security.filter.MerchantAuthFilter;
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
 * 商家端独立安全过滤器链，只作用于 {@code /api/merchant/**}。
 *
 * <p><b>为什么现有的 SecurityConfig 一行都不用改</b>：它那条链没有 {@code @Order}，
 * 因此取默认的 {@code LOWEST_PRECEDENCE}。Spring Security 只执行**第一个匹配到的链**，
 * 而本链用 {@code @Order(1)} + {@code securityMatcher} 先匹配，于是商家路径完全不走
 * C 端那套（含 JwtAuthFilter），其余路径也完全不走本链。</p>
 */
@Configuration
@EnableWebSecurity
public class MerchantSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain merchantFilterChain(HttpSecurity http, MerchantJwtUtil merchantJwtUtil)
            throws Exception {
        http
            .securityMatcher("/api/merchant/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/merchant/login").permitAll()
                .anyRequest().hasRole("MERCHANT")
            )
            // 直接 new，不作为 bean 暴露，避免被 Spring Boot 注册成全局 Servlet 过滤器
            .addFilterBefore(new MerchantAuthFilter(merchantJwtUtil),
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
