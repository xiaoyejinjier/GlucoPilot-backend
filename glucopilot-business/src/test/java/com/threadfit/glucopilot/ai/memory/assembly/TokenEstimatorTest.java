package com.threadfit.glucopilot.ai.memory.assembly;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.threadfit.glucopilot.ai.memory.assembly.TokenEstimator;

/**
 * token 估算与校准。
 * <p>
 * 估算器只影响"装多少历史"，但它错了会导致两个方向的问题：高估 → 历史被过度裁掉（模型失忆）；
 * 低估 → prompt 超上下文（报错或静默截断）。因此这里既钉住基准比例，也钉住校准的方向性
 * （真实比估算大就该往上调）与<b>边界夹紧</b>（防止被一次异常值带跑）。
 */
class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void 中文按一字一token估算() {
        assertEquals(6, estimator.estimate("血糖记录解读"));
    }

    @Test
    void 英文按四字符一token估算() {
        // 16 个 ASCII 字符 ≈ 4 token
        assertEquals(4, estimator.estimate("abcdefghijklmnop"));
    }

    @Test
    void 中英混排分别计价() {
        // 6 个汉字 + 8 个 ASCII ≈ 6 + 2 = 8
        assertEquals(8, estimator.estimate("血糖记录解读abcdefgh"));
    }

    @Test
    void 空白不计入() {
        assertEquals(0, estimator.estimate("   \n\t "));
        assertEquals(0, estimator.estimate(""));
        assertEquals(0, estimator.estimate(null));
    }

    @Test
    void 每条消息另计固定开销() {
        assertEquals(estimator.estimate("你好") + 4, estimator.estimateMessage("你好"));
    }

    @Test
    void 真实值偏大时向上校准() {
        estimator.calibrate(100, 50);   // 真实是估算的两倍

        assertTrue(estimator.factor() > 1.0, "应向上调整，实际系数=" + estimator.factor());
        assertTrue(estimator.estimate("血糖记录解读") > 4, "校准后同样的文本估算应更大");
    }

    @Test
    void 真实值偏小时向下校准() {
        estimator.calibrate(25, 50);

        assertTrue(estimator.factor() < 1.0, "应向下调整，实际系数=" + estimator.factor());
    }

    @Test
    void 校准被夹在合理区间内() {
        // 连续极端样本也不该把系数带飞：估算器只负责"装多少历史"，不该被单点主导
        for (int i = 0; i < 50; i++) {
            estimator.calibrate(100_000, 1);
        }
        assertTrue(estimator.factor() <= 2.5, "上限夹紧，实际=" + estimator.factor());

        TokenEstimator low = new TokenEstimator();
        for (int i = 0; i < 50; i++) {
            low.calibrate(1, 100_000);
        }
        assertTrue(low.factor() >= 0.5, "下限夹紧，实际=" + low.factor());
    }

    @Test
    void 无效样本被忽略() {
        estimator.calibrate(0, 100);
        estimator.calibrate(100, 0);
        estimator.calibrate(-5, -5);

        assertEquals(1.0, estimator.factor(), 0.0001, "无效样本不该改变系数");
    }

    @Test
    void 校准后再重置能回到基准() {
        estimator.calibrate(200, 100);
        assertTrue(estimator.factor() > 1.0);

        estimator.resetCalibration();
        assertEquals(1.0, estimator.factor(), 0.0001);
    }
}
