ALTER TABLE cms_news
    ADD COLUMN title_pinyin VARCHAR(512) NULL COMMENT '标题全拼小写连写（无调）' AFTER title,
    ADD COLUMN title_pinyin_initials VARCHAR(128) NULL COMMENT '标题声母小写连写' AFTER title_pinyin;
