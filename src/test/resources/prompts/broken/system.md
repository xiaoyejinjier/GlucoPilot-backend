# 测试夹具：故意保留一个未声明的模板变量
本文件只用于单测（AiPromptTemplateServiceTest），验证「模板里出现未声明的花括号占位符时，
启动阶段就必须失败」这条护栏。

用户补充的食物描述：{foodText}
