package com.threadfit.glucopilot.device.cloud;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 华为账号 OAuth：拼授权 URL、code 换 token、refresh。
 */
@Component
public class HuaweiOAuthClient {

    private final HuaweiCloudProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HuaweiOAuthClient(
            DeviceCloudProperties cloudProperties,
            @Qualifier("deviceCloudRestClient") RestClient restClient,
            ObjectMapper objectMapper) {
        this.props = cloudProperties.getHuawei() != null ? cloudProperties.getHuawei() : new HuaweiCloudProperties();
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public String buildAuthorizeUrl(String state) {
        requireConfigured();
        return UriComponentsBuilder.fromUriString(props.getAuthorizeBase())
                .queryParam("response_type", "code")
                .queryParam("client_id", props.getClientId())
                .queryParam("redirect_uri", props.getRedirectUri())
                .queryParam("scope", props.getScopes())
                .queryParam("access_type", "offline")
                .queryParam("state", state)
                .build(true)
                .toUriString();
    }

    public TokenBundle exchangeCode(String code) {
        requireConfigured();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());
        form.add("redirect_uri", props.getRedirectUri());
        return postToken(form);
    }

    public TokenBundle refresh(String refreshToken) {
        requireConfigured();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());
        return postToken(form);
    }

    private TokenBundle postToken(MultiValueMap<String, String> form) {
        try {
            String body = restClient.post()
                    .uri(URI.create(props.getTokenUrl()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(Objects.requireNonNullElse(body, "{}"));
            String access = text(root, "access_token");
            if (!StringUtils.hasText(access)) {
                throw new BizException(ErrorCode.BIZ_REJECTED, "华为授权换票失败");
            }
            String refresh = text(root, "refresh_token");
            String openid = firstNonBlank(text(root, "open_id"), text(root, "openid"), text(root, "sub"));
            if (!StringUtils.hasText(openid)) {
                openid = "huawei-" + Integer.toHexString(access.hashCode());
            }
            long expiresIn = root.path("expires_in").asLong(3600L);
            LocalDateTime expiresAt = LocalDateTime.ofInstant(
                    Instant.now().plusSeconds(Math.max(60, expiresIn)),
                    ZoneId.of("Asia/Shanghai"));
            String scope = text(root, "scope");
            return new TokenBundle(access, refresh, openid, expiresAt, scope);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "华为授权换票失败");
        }
    }

    private void requireConfigured() {
        if (!props.isEnabled()
                || !StringUtils.hasText(props.getClientId())
                || !StringUtils.hasText(props.getClientSecret())
                || !StringUtils.hasText(props.getRedirectUri())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "华为健康云未启用或未配置");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return null;
    }

    public record TokenBundle(
            String accessToken,
            String refreshToken,
            String vendorSubject,
            LocalDateTime expiresAt,
            String scopes) {
    }
}
