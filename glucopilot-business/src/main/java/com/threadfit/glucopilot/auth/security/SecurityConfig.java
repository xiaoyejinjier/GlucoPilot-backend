package com.threadfit.glucopilot.auth.security;

import com.ruoyi.framework.web.service.UserDetailsServiceImpl;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.util.JsonUtils;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  private final JsonUtils json;

  public SecurityConfig(JsonUtils json) {
    this.json = json;
  }

  @Bean
  public AuthenticationManager authenticationManager(
      UserDetailsServiceImpl details, PasswordEncoder encoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(details);
    provider.setPasswordEncoder(encoder);
    return new ProviderManager(provider);
  }

  private HttpSecurity common(HttpSecurity http) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                        (req, res, e) -> {
                          res.setStatus(401);
                          res.setContentType("application/json;charset=UTF-8");
                          res.getWriter()
                              .write(json.toJson(ApiResponse.fail(ErrorCode.UNAUTHORIZED)));
                        })
                    .accessDeniedHandler(
                        (req, res, e) -> {
                          res.setStatus(403);
                          res.setContentType("application/json;charset=UTF-8");
                          res.getWriter().write(json.toJson(ApiResponse.fail(ErrorCode.FORBIDDEN)));
                        }));
  }

  @Bean
  @Order(1)
  public SecurityFilterChain userChain(HttpSecurity http, UserSessionAuthFilter filter)
      throws Exception {
    return common(http)
        .securityMatcher("/api/v1/**")
        .authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.PUT,
                        "/api/v1/media-files/local-uploads/*",
                        "/api/v1/media-files/minio-uploads/*")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/wechat/sessions",
                        "/api/v1/auth/sms/codes",
                        "/api/v1/auth/phone/sessions",
                        "/api/v1/auth/email/codes",
                        "/api/v1/auth/email/sessions")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/banners",
                        "/api/v1/news",
                        "/api/v1/news/*",
                        "/api/v1/food-categories",
                        "/api/v1/foods",
                        "/api/v1/foods/*",
                        "/api/v1/foods/images/*",
                        "/api/v1/cms/images/*",
                        "/api/v1/cms/videos/*",
                        "/api/v1/dict/**",
                        "/api/v1/app-config",
                        "/api/v1/media-files/local-objects")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  @Order(2)
  public SecurityFilterChain opsChain(HttpSecurity http, OpsSessionAuthFilter filter)
      throws Exception {
    return common(http)
        .securityMatcher("/admin/v1/**")
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/admin/v1/auth/sessions")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  @Order(3)
  public SecurityFilterChain fallbackChain(HttpSecurity http) throws Exception {
    return common(http)
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .build();
  }
}
