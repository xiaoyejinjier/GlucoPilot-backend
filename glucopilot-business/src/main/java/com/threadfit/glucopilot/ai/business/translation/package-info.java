/**
 * 中英翻译能力：文本进，英文出。
 *
 * <p>缓存表、租约、批量与权限闸都在 localization 模块（{@code LocalizationService}）；这里只管"把这段中文翻成英文并保证数字/单位不走样"，
 * 连提示词版本也只是通过 {@code promptVersion()} 汇报，由调用方决定要不要放进缓存键。
 *
 * <p>依赖方向固定为 {@code localization → ai}：本包不 import 任何业务模块。
 */
package com.threadfit.glucopilot.ai.business.translation;