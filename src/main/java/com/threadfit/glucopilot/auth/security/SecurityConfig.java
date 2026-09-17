package com.threadfit.glucopilot.auth.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * C/B 端鉴权：公开读 + 登录放行；C=/api/v1/**，B=/admin/v1/**。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            UserSessionAuthFilter userSessionAuthFilter,
            OpsSessionAuthFilter opsSessionAuthFilter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/wechat/sessions",
                                "/api/v1/auth/sms/codes",
                                "/api/v1/auth/phone/sessions",
                                "/admin/v1/auth/sessions").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/banners",
                                "/api/v1/news",
                                "/api/v1/news/*",
                                "/api/v1/food-categories",
                                "/api/v1/foods",
                                "/api/v1/dict/**",
                                "/api/v1/app-config").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .requestMatchers("/admin/v1/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(opsSessionAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(userSessionAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setStatus(401);
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write(
                                    "{\"code\":40100,\"message\":\"未登录或 Token 失效\",\"data\":null,\"requestId\":null}");
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            res.setStatus(403);
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write(
                                    "{\"code\":40300,\"message\":\"无权限、账号禁用或注销中\",\"data\":null,\"requestId\":null}");
                        }));
        return http.build();
    }
}
