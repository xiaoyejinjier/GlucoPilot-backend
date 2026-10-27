-- Only live food names are unique. Multiple deleted versions must retain their history.
-- A NULL marker lets MySQL keep any number of tombstones without changing their names.
ALTER TABLE food_nutrition
    ADD COLUMN active_name_marker TINYINT GENERATED ALWAYS AS
        (CASE WHEN is_deleted = 0 THEN 1 ELSE NULL END) VIRTUAL,
    DROP INDEX uk_cat_name,
    ADD UNIQUE KEY uk_food_active_name (category_code, food_name, active_name_marker);
