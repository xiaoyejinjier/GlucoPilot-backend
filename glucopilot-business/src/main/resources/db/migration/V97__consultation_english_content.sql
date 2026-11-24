-- 扩展现有翻译缓存类型；问诊私密内容始终在Service逐次鉴权后读取。
ALTER TABLE content_localization DROP CHECK chk_cl_kind;
ALTER TABLE content_localization
  ADD CONSTRAINT chk_cl_kind CHECK (kind IN (
    'ARTICLE_PREVIEW', 'ARTICLE_BODY', 'PROFILE_NAME',
    'CONSULTATION_PREVIEW', 'CONSULTATION_DETAIL', 'CONSULTATION_REPORT'
  ));
