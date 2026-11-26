package com.threadfit.glucopilot.food.gi;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.util.List;

/** GI查找与条件回填SQL集中在同路径的Mapper XML。 */
@Mapper
public interface FoodGiMapper {
    @Data
    class Candidate {
        private Long foodId;
        private String foodName;
        private Integer categoryCode;
    }

    @Data
    class Reference {
        private String status;
        private BigDecimal giValue;
        private String referenceFood;
        private String sourceUrl;
        private String note;
        public FoodGiResult result() {
            return new FoodGiResult(status, giValue, referenceFood, sourceUrl, note);
        }
    }

    @Data
    class Demo {
        private Long foodId;
        private BigDecimal giDemoValue;
    }

    List<Demo> demoValues(@Param("ids") List<Long> ids);

    int enqueueMissing();
    List<Candidate> pending();
    Reference reference(@Param("name") String name, @Param("category") Integer category);
    int claim(@Param("item") Candidate item, @Param("lease") String lease);
    Long lockClaim(@Param("id") Long id, @Param("lease") String lease);
    int fillGi(@Param("item") Candidate item, @Param("value") BigDecimal value);
    int complete(@Param("item") Candidate item, @Param("lease") String lease,
                 @Param("result") FoodGiResult result);
}
