/**
 * 工具层的<b>端口</b>：由 {@code ai} 声明契约、由业务模块实现。
 *
 * <p>为什么工具需要这一层：{@code ai} 模块有一条硬约束（{@code AiModuleBoundaryTest} 守着）——
 * 不许 import {@code community} / {@code localization}。而「用户问社区里有没有人讨论过这个话题」
 * 这类工具必须读社区的数据，于是把「查什么、返回什么」定义成端口留在 ai，
 * 把「怎么查」留给表的主人实现，依赖方向仍然是 {@code community → ai}。
 *
 * <p>端口里的记录类型只有一个职责：<b>给模型的裁剪视图</b>。
 * 不要在这里透出实体、正文全文或任何内部标识（用户 id、审核字段）。
 */
package com.threadfit.glucopilot.ai.tool.port;
