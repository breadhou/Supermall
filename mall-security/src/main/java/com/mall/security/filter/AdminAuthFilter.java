package com.mall.security.filter;

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
 * 平台管理端认证过滤器，只挂在 {@code /api/admin/**} 上。
 *
 * <p>与商家端共用签发密钥（{@link MerchantJwtUtil}）——两者都是内部后台，
 * 再拆第三个密钥不带来额外隔离收益。真正的门槛是角色：只有 {@code role}
 * 为 {@code SUPER_ADMIN} 的 token 才拿到 {@code ROLE_SUPER_ADMIN}，
 * 商家账号的 token 签名能过、权限过不了。</p>
 *
 * <p>和 MerchantAuthFilter 一样**刻意不是 {@code @Component}**，避免被
 * Spring Boot 自动注册成全局 Servlet 过滤器。</p>
 */
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final String SUPER_ADMIN = "SUPER_ADMIN";

    private final MerchantJwtUtil merchantJwtUtil;

    public AdminAuthFilter(MerchantJwtUtil merchantJwtUtil) {
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
                if (SUPER_ADMIN.equals(claims.get("role", String.class))) {
                    Long adminUserId = Long.valueOf(claims.getSubject());
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            adminUserId, null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
