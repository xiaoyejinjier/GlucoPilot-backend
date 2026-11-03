package com.threadfit.glucopilot.food.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Redis专用不可变JSON结构，与HTTP DTO分开，避免影响既有接口和Lombok构造方式。 */
public final class FoodCacheSnapshots {
    private FoodCacheSnapshots() {}

    public record Categories(List<Category> items) {
        public Categories {
            items = List.copyOf(items);
        }

        public static Categories from(List<FoodDtos.FoodCategoryItem> items) {
            return new Categories(items.stream().map(Category::from).toList());
        }

        public List<FoodDtos.FoodCategoryItem> toItems() {
            return items.stream().map(Category::toItem).toList();
        }
    }

    public record Category(String id, String categoryName, String imageUrl, Integer sortNo) {
        public Category {
            Objects.requireNonNull(id);
        }

        public static Category from(FoodDtos.FoodCategoryItem item) {
            return new Category(
                    item.getId(), item.getCategoryName(), item.getImageUrl(), item.getSortNo());
        }

        public FoodDtos.FoodCategoryItem toItem() {
            return FoodDtos.FoodCategoryItem.builder()
                    .id(id)
                    .categoryName(categoryName)
                    .imageUrl(imageUrl)
                    .sortNo(sortNo)
                    .build();
        }
    }

    public record Food(
            String id,
            String foodName,
            Integer categoryCode,
            String categoryId,
            String categoryName,
            BigDecimal carbsPer100g,
            BigDecimal caloriesPer100g,
            BigDecimal giValue,
            String imageUrl) {
        public Food {
            Objects.requireNonNull(id);
        }

        public static Food from(FoodDtos.FoodItem item) {
            return new Food(
                    item.getId(),
                    item.getFoodName(),
                    item.getCategoryCode(),
                    item.getCategoryId(),
                    item.getCategoryName(),
                    item.getCarbsPer100g(),
                    item.getCaloriesPer100g(),
                    item.getGiValue(),
                    item.getImageUrl());
        }

        public FoodDtos.FoodItem toItem() {
            return FoodDtos.FoodItem.builder()
                    .id(id)
                    .foodName(foodName)
                    .categoryCode(categoryCode)
                    .categoryId(categoryId)
                    .categoryName(categoryName)
                    .carbsPer100g(carbsPer100g)
                    .caloriesPer100g(caloriesPer100g)
                    .giValue(giValue)
                    .imageUrl(imageUrl)
                    .build();
        }
    }
}
