package com.threadfit.glucopilot.ai.business.chat.service;

import com.threadfit.glucopilot.ai.business.chat.dto.ChatDtos;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * AI 流式补全契约（接口 2.25）。
 *
 * <h2>为什么返回 Flux 而不是自己管 SseEmitter</h2>
 * 交给 MVC 的 ReactiveTypeHandler 后，客户端断开/超时的处理由框架负责：
 * 「取消的轮次不落库」变成结构上的保证（concatWith(done) 里的落库不会执行），
 * 而不是靠几处 dispose 兜住。
 *
 * <h2>载荷为什么是 UTF-8 原始字节</h2>
 * SSE 的 data 分片在框架里不带 media type，交给 StringHttpMessageConverter 时
 * charset 取决于响应内容类型——实测中文会被写成问号。ByteArrayHttpMessageConverter
 * 支持任意类型且原样输出字节，彻底绕开 charset 判断。
 *
 * <h2>实现必须守住的语义</h2>
 * <ul>
 *   <li><b>开流前的校验失败仍抛业务异常</b>（会话不属于我/已结束、replyToMsgId 无效），
 *       客户端拿到的是统一 JSON 而不是流到一半的 error 事件；</li>
 *   <li><b>幂等</b>：本轮已生成过回复时直接重放 done，不重复扣费；</li>
 *   <li><b>单飞</b>：同一条用户消息同时只允许一次生成，且拦在调用模型<b>之前</b>；</li>
 *   <li>开流后的错误走 error 事件，且「已有内容则落库并标 4」。</li>
 * </ul>
 */
public interface ChatCompletionService {

    Flux<ServerSentEvent<byte[]>> complete(Long userId, Long sessionId, ChatDtos.CompletionRequest request);
}