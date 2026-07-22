package com.mall.security.filter;

import com.mall.security.utils.JwtUtil;
import com.mall.security.utils.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT 认证过滤器。
 * 每个请求进来时，从 Authorization 请求头中提取 Token，
 * 验证通过后将用户信息写入 SecurityContext 和 UserContext，
 * 供后续 Controller/Service 使用。
 */
public class JwtAuthFilter extends OncePerRequestFilter {  // OncePerRequestFilter 保证每个请求只执行一次

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 1. 从请求头中取出 Authorization
        String header = request.getHeader("Authorization");

        // 2. 检查是否以 "Bearer " 开头（JWT 标准格式）
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);  // 去掉 "Bearer " 前缀，剩下的就是 Token

            // 3. 验证 Token 是否有效（签名正确 + 未过期）
            if (JwtUtil.validate(token)) {
                // 4. 从 Token 中解析出 userId
                Long userId = JwtUtil.getUserId(token);

                // 5. 存入 UserContext，业务代码直接 UserContext.getUserId() 就能拿到
                UserContext.setUserId(userId);

                // 6. 构建认证对象，写入 Spring Security 上下文
                //    Spring Security 用它判断"当前请求是否已认证"
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        // 7. 放行请求，交给下一个过滤器或 Controller 处理
        filterChain.doFilter(request, response);

        // 8. 请求结束，清除 ThreadLocal，防止内存泄漏
        UserContext.clear();
    }
}
