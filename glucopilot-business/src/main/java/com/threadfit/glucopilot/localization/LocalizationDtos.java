package com.threadfit.glucopilot.localization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

public final class LocalizationDtos {
    private LocalizationDtos() {}
    public record Item(@NotBlank @Pattern(regexp="ARTICLE_PREVIEW|ARTICLE_BODY|PROFILE_NAME") String kind,
                       @NotBlank @Pattern(regexp="[ub]?[1-9][0-9]{0,18}") String id) {}
    public record Request(@NotEmpty @Size(max=20) List<@NotNull @Valid Item> items, Boolean retry) {}
    public record Result(String kind, String id, String status, String sourceHash,
                         Map<String,String> source, Map<String,String> translated) {}
}
