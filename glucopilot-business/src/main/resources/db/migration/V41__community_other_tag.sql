-- Official categories remain administrator managed; custom_* tags are grouped under other.
INSERT INTO community_tag(code,name,sort_no,enabled)
SELECT 'other','其他',9999,1
WHERE NOT EXISTS (SELECT 1 FROM community_tag WHERE code='other');
