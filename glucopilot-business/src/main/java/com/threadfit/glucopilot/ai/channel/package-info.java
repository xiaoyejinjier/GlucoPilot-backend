/**
 * 通道：一套「协议 + 连接参数 + 凭据」。
 * <p>
 * {@code AiChannel} 是通道枚举，{@code AiChannelProperties} 是它在 yaml 里的形状（{@code ai.channels.text|vision}）。
 * <p>
 * 通道不负责「模型是谁」（那是 ai_model_config 的 model_code）也不负责「场景要什么」（那是 SceneCode 与 capability）。
 */
package com.threadfit.glucopilot.ai.channel;
