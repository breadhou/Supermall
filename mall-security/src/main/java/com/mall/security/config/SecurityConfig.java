package com.mall.security.config;

import com.mall.security.filter.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 核心配置。
 *
 * 核心策略：无状态 JWT 认证，不依赖 Session。
 * - 白名单路径：登录/注册、Swagger 文档页 → 直接放行
 * - 其余所有请求 → 必须携带有效 JWT
 */
@Configuration
@EnableWebSecurity  // 开启 Spring Security 的 Web 安全能力
public class SecurityConfig {

    // 密码加密器：BCrypt 是单向哈希，不可逆，比 MD5 安全
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 把自定义的 JWT 过滤器声明为 Bean
    @Bean
    public JwtAuthFilter jwtAuthFilter() {
        return new JwtAuthFilter();
    }

    // 安全过滤器链：定义哪些路径需要认证，哪些路径放行
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 关闭 CSRF 防护：前后端分离 + JWT 场景下不需要
            .csrf(csrf -> csrf.disable())

            // 无状态 Session：服务器不存任何会话信息，每次请求独立认证
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 路径权限配置
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/api/auth/**",       // 登录、注册接口
                        "/doc.html",          // Knife4j 文档页
                        "/webjars/**",        // Knife4j 静态资源
                        "/v3/api-docs/**",    // OpenAPI 接口数据
                        "/swagger-ui/**"      // Swagger UI 资源
                ).permitAll()                  // ↑ 以上路径无需认证
                .anyRequest().authenticated()  // ↓ 其余路径必须携带有效 Token
            )

            // 把我们的 JWT 过滤器插在 Spring Security 的默认认证过滤器之前
            // 这样 JWT 认证先执行，通过了就不会走默认的表单登录流程
            .addFilterBefore(jwtAuthFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
