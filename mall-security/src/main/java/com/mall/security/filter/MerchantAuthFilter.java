package com.mall.security.filter;

import com.mall.security.utils.MerchantContext;
import com.mall.security.utils.MerchantJwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 商家端 JWT 认证过滤器。与 C 端的 JwtAuthFilter 互不相干。
 *
 * <p>刻意**不声明为 {@code @Component}**：Spring Boot 会把容器里任何 Filter bean
 * 自动注册到全局 Servlet 过滤链上，那样它就会对所有路径生效。这里由
 * MerchantSecurityConfig 直接 new 出来并只挂到商家链上。</p>
 */
public class MerchantAuthFilter extends OncePerRequestFilter {

    private final MerchantJwtUtil merchantJwtUtil;

    public MerchantAuthFilter(MerchantJwtUtil merchantJwtUtil) {
        this.merchantJwtUtil = merchantJwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (merchantJwtUtil.validate(token)) {
                Claims claims = merchantJwtUtil.parse(token);
                Long adminUserId = Long.valueOf(claims.getSubject());
                Long merchantId = claims.get("merchantId", Long.class);

                // 只有绑定了商家的账号才获得商家权限；SUPER_ADMIN 的 token 没有
                // merchantId，通过鉴权但拿不到 ROLE_MERCHANT。
                if (merchantId != null) {
                    MerchantContext.set(adminUserId, merchantId);
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            adminUserId, null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MerchantContext.clear();
        }
    }
}
