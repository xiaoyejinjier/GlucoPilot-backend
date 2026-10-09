package com.threadfit.glucopilot.adapter;

import com.ruoyi.framework.security.filter.JwtAuthenticationTokenFilter;
import com.threadfit.glucopilot.auth.security.OpsSessionAuthFilter;
import com.threadfit.glucopilot.auth.security.UserSessionAuthFilter;
import jakarta.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Authentication filters run only in their matching SecurityFilterChain. */
@Configuration
public class FilterConfiguration {
  @Bean
  public FilterRegistrationBean<JwtAuthenticationTokenFilter> upstreamJwtRegistration(
      JwtAuthenticationTokenFilter filter) {
    return disabled(filter);
  }

  @Bean
  public FilterRegistrationBean<OpsSessionAuthFilter> opsRegistration(OpsSessionAuthFilter filter) {
    return disabled(filter);
  }

  @Bean
  public FilterRegistrationBean<UserSessionAuthFilter> userRegistration(
      UserSessionAuthFilter filter) {
    return disabled(filter);
  }

  private <T extends Filter> FilterRegistrationBean<T> disabled(T filter) {
    FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }
}
