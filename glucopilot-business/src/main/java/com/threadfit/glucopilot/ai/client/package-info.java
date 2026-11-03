/**
 * ChatClient 与 ChatModel 的取用入口（{@code AiChatClientRegistry}）。
 * <p>
 * 业务层不直接注入 ChatClient/ChatModel：统一走这里，才能保证「拿不到就抛」的语义一致、避免各处分叉成 @Qualifier 或 ObjectProvider。
 */
package com.threadfit.glucopilot.ai.client;
