package com.threadfit.glucopilot.ai.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.threadfit.glucopilot.ai.model.entity.AiCallLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型调用账本读写（V75）。只追加：除了「注销清理」与「过期删除」没有别的写操作。
 *
 * <p>继承 {@link BaseMapper} 不是为了用它自带的 CRUD，而是因为 Spring 侧的
 * {@code PersistenceConfiguration.BusinessMappers} 只扫描继承它的接口——不继承的话
 * {@code @Mapper} 也注册不上（踩过，B 批用量报表时整条链路 404）。
 */
public interface AiCallLogMapper extends BaseMapper<AiCallLog> {

    /**
     * 注销时清掉该用户全部账本行。
     *
     * <p>它是<u>第五张</u>含 {@code user_id} 的会话相关表（前三张是消息/分片/工具调用，第四张是会话本身）。
     * 少清一张的后果不是"内存泄漏"，而是"注销半年后还能查到这个人何时用过哪个场景"——
     * 那属于注销承诺没兑现，而不是统计口径问题。
     */
    @Delete("DELETE FROM ai_call_log WHERE user_id = #{userId}")
    int purgeByUserId(@Param("userId") Long userId);

    /**
     * 分批删过期行（由 {@code AiCallLogCleanupJob} 循环调用直到返回 0）。
     *
     * <p>为什么带 {@code LIMIT}：一次删几百万行会长时间持锁、撑大 undo，还可能把从库拖垮。
     * 账单是低价值数据，慢一点删没有任何代价。
     */
    @Delete("DELETE FROM ai_call_log WHERE gmt_create < #{before} LIMIT #{limit}")
    int deleteOlderThan(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /**
     * 用量报表：按「天 × 场景 × 模型」聚合。
     *
     * <p><b>半开区间</b> {@code [from, to)}：调用方把结束日的 23:59:59.999 也算进来，靠的就是
     * {@code to = 次日零点}，所以这里不需要 {@code <=}。
     *
     * <p>为什么把失败次数也聚合出来：{@code ok = 0} 的行同样消耗了上游配额（很多厂商对超时/
     * 参数错也计费，或者至少占用并发），报表里如果只算成功，会把"这一小时一直在失败重试"的成本
     * 显示成零。
     *
     * <p>{@code cache_hit_tokens/cache_miss_tokens} 用 {@code COALESCE} 归零：账单里 NULL 表示
     * "这次没上报命中"，聚合时按 0 计是因为「命中率」的分母是"有上报的调用"，见 service 侧说明。
     */
    @Select("""
            SELECT CAST(gmt_create AS DATE) AS usage_day,
                   COALESCE(scene_code, '') AS scene_code,
                   COALESCE(model_code, '') AS model_code,
                   COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COALESCE(SUM(cache_hit_tokens), 0) AS cache_hit_tokens,
                   COALESCE(SUM(cache_miss_tokens), 0) AS cache_miss_tokens,
                   COUNT(*) AS calls,
                   COALESCE(SUM(CASE WHEN ok = 0 THEN 1 ELSE 0 END), 0) AS failed_calls
            FROM ai_call_log
            WHERE gmt_create >= #{from} AND gmt_create < #{to}
            GROUP BY CAST(gmt_create AS DATE), COALESCE(scene_code, ''), COALESCE(model_code, '')
            ORDER BY usage_day DESC, scene_code ASC, model_code ASC
            """)
    List<AiCallUsageRow> dailyUsage(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
