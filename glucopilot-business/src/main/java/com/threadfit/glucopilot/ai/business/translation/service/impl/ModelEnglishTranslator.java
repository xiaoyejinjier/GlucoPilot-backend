package com.threadfit.glucopilot.ai.business.translation.service.impl;

import com.threadfit.glucopilot.ai.client.AiChatClientRegistry;
import com.threadfit.glucopilot.ai.prompt.service.AiPromptTemplateService;
import com.threadfit.glucopilot.ai.routing.SceneModelResolver;
import com.threadfit.glucopilot.ai.scene.SceneCode;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import com.threadfit.glucopilot.ai.business.translation.service.EnglishTranslator;

/**
 * 中英翻译能力实现：只用「翻译器」这一种角色跟模型说话——没有医疗护栏、没有人设、没有记忆、
 * 没有工具，因此走 AI 模块的 utility 客户端（无 advisor 链的裸客户端）。
 *
 * <p><b>提示词不再写死在代码里</b>：正文在 {@code ai_prompt_template} 的 {@code TRANSLATION}
 * 场景（V54 播种，classpath 兜底 {@code prompts/translation/system.md}），后台可改、可回滚；
 * 版本标签（{@code translation@v1}）同时进缓存哈希与缓存表留痕，换提示词即刻全量重翻。
 *
 * <p><b>「只翻文本节点」的边界在这一层守住</b>：调用方给的字符串里若含 HTML，标签、注释、
 * script/style 段都不进模型（见 {@link #parts}），模型只看到纯文本；翻回来再按原样拼装。
 * 数字、单位、URL 在 {@link #validate} 里逐项核对——医学内容里改一个数字就是事故。
 */
@Component
public class ModelEnglishTranslator implements EnglishTranslator {
    static final Pattern HAN=Pattern.compile("\\p{IsHan}");
    private static final Pattern TOKENS=Pattern.compile("(?is)<!--.*?-->|<(script|style)\\b[^>]*>.*?</\\1\\s*>|</?[a-z][a-z0-9:-]*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^'\\\">])*>");
    private static final Pattern NUMBERS=Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern SOURCE_NUMBERS=Pattern.compile("([0-9]+(?:\\.[0-9]+)?)|(?<![\\p{IsHan}A-Za-z0-9])((?:[一二三四五六七八九]?十[一二三四五六七八九]?|[一二三四五六七八九]))[、．]");
    private static final Pattern URLS=Pattern.compile("https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
    private static final Pattern UNITS=Pattern.compile("(?i)mmol\\s*/\\s*L|mg\\s*/\\s*dL|(?<![A-Za-z])(?:mg|mcg|ug|μg|IU|kg|kcal)(?![A-Za-z])");
    /** 第一次不合法时追加的纠正指令：只补格式要求，不动业务规则（业务规则在提示词里）。 */
    private static final String RETRY_HINT=
            "\nThe prior response could not be validated. Return valid JSON with all requested string fields, without missing or changing any numeric values or units.";
    private final AiChatClientRegistry clients;
    private final AiPromptTemplateService prompts;
    private final SceneModelResolver sceneModelResolver;
    private final ObjectMapper json;
    public ModelEnglishTranslator(AiChatClientRegistry clients,AiPromptTemplateService prompts,
                                  SceneModelResolver sceneModelResolver,ObjectMapper json) {
        this.clients=clients;this.prompts=prompts;this.sceneModelResolver=sceneModelResolver;this.json=json;
    }
    @Override public String promptVersion() {
        return prompts.versionTag(SceneCode.TRANSLATION);
    }
    record Part(String value,boolean translate,boolean html) {}
    static List<Part> parts(String value,boolean body) {
        if(!body || !TOKENS.matcher(value).find())return split(value,false);
        var result=new ArrayList<Part>();var matcher=TOKENS.matcher(value);int end=0;
        while(matcher.find()) {
            result.addAll(split(HtmlUtils.htmlUnescape(value.substring(end,matcher.start())),true));
            result.add(new Part(matcher.group(),false,false));end=matcher.end();
        }
        result.addAll(split(HtmlUtils.htmlUnescape(value.substring(end)),true));return result;
    }
    private static List<Part> split(String value,boolean html) {
        var result=new ArrayList<Part>();
        var lines=Pattern.compile("\\r\\n|\\r|\\n").matcher(value);int from=0;
        while(lines.find()) {result.addAll(line(value.substring(from,lines.start()),html));result.add(new Part(lines.group(),false,html));from=lines.end();}
        result.addAll(line(value.substring(from),html));return result;
    }
    private static List<Part> line(String value,boolean html) {
        var result=new ArrayList<Part>();
        for(int start=0;start<value.length();) {
            int end=Math.min(value.length(),start+1200);
            if(end<value.length())for(int n=end-1;n>start+600;n--)if("。！？；，.!?;, ".indexOf(value.charAt(n))>=0){end=n+1;break;}
            // Never split a surrogate pair.
            if(end<value.length()&&Character.isHighSurrogate(value.charAt(end-1)))end--;
            String part=value.substring(start,end);result.add(new Part(part,HAN.matcher(part).find(),html));start=end;
        }
        return result;
    }
    @Override public Map<String,String> translate(String kind,Map<String,String> source) {
        var layout=new LinkedHashMap<String,List<Part>>();var segments=new LinkedHashMap<String,String>();
        for(var entry:source.entrySet()) {
            var parts=parts(entry.getValue(),entry.getKey().equals("body"));layout.put(entry.getKey(),parts);
            for(int i=0;i<parts.size();i++)if(parts.get(i).translate())segments.put(entry.getKey()+":"+i,parts.get(i).value());
        }
        if(segments.values().stream().mapToInt(String::length).sum()>16000)throw new IllegalArgumentException("Article too long for translation");
        var translated=new LinkedHashMap<String,String>();
        var batch=new LinkedHashMap<String,String>();int length=0;
        for(var entry:segments.entrySet()) {
            if(!batch.isEmpty()&&(batch.size()>=24||length+entry.getValue().length()>3200)) {
                translated.putAll(call(kind,batch));batch.clear();length=0;
            }
            batch.put(entry.getKey(),entry.getValue());length+=entry.getValue().length();
        }
        if(!batch.isEmpty())translated.putAll(call(kind,batch));
        var result=new LinkedHashMap<String,String>();
        for(var entry:layout.entrySet()) {
            var out=new StringBuilder();var parts=entry.getValue();
            for(int i=0;i<parts.size();i++) {
                var part=parts.get(i);String value=part.translate()?translated.get(entry.getKey()+":"+i):part.value();
                if(part.translate()) {
                    var edges=Pattern.compile("^(\\s*)([\\s\\S]*?)(\\s*)$").matcher(part.value());edges.matches();
                    value=edges.group(1)+value.strip()+edges.group(3);
                    if(i>0&&parts.get(i-1).translate()&&out.length()>0&&!Character.isWhitespace(out.charAt(out.length()-1))&&!value.isEmpty()&&!Character.isWhitespace(value.charAt(0)))out.append(' ');
                }
                out.append(part.html()?HtmlUtils.htmlEscape(value):value);
            }
            result.put(entry.getKey(),out.toString());
        }
        return result;
    }
    private Map<String,String> call(String kind,Map<String,String> source) {
        // 与其他业务一样的取法：走 registry 的 utility 客户端（无 advisor、无工具的裸客户端）。
        // 提示词、温度、max-tokens、JSON 模式全部来自 TRANSLATION 场景配置，代码里不再写死模型参数。
        ChatClient client=clients.utilityClient();
        var options=sceneModelResolver.optionsFor(SceneCode.TRANSLATION);
        String system=prompts.systemPrompt(SceneCode.TRANSLATION);
        String data=json.writeValueAsString(source);
        for(int attempt=0;attempt<2;attempt++) {
            // 必须用 messages(...) 而不是 user(...)：后者会把入参当模板渲染，
            // 而这里要塞的是 JSON（含花括号），交给 ST 渲染器会被当成变量表达式直接炸掉。
            var requestSpec=client.prompt()
                    .messages(new SystemMessage(system+"\nContent category: "+kind+(attempt==0?"":RETRY_HINT)),
                              new UserMessage(data));
            if(options!=null)requestSpec=requestSpec.options(options);
            var spec=requestSpec;
            var response=Mono.fromCallable(()->spec.call().chatResponse())
                    .subscribeOn(Schedulers.boundedElastic()).timeout(Duration.ofSeconds(60)).block();
            String text=response==null||response.getResult()==null?"":response.getResult().getOutput().getText();
            try{return parse(text,source,json);}catch(RuntimeException ex){if(attempt==1||Thread.currentThread().isInterrupted())throw ex;}
        }
        throw new IllegalStateException("Translation could not be validated");
    }
    static Map<String,String> parse(String text,Map<String,String> source,ObjectMapper json) {
        if(text==null||text.length()>100000)throw new IllegalArgumentException("Invalid translation");
        String value=text.trim();if(value.startsWith("```json")&&value.endsWith("```"))value=value.substring(7,value.length()-3).trim();
        var node=json.readTree(value);if(!node.isObject()||node.size()<source.size())throw new IllegalArgumentException("Invalid translation fields");
        var result=new LinkedHashMap<String,String>();
        for(var entry:source.entrySet()) {
            var translated=node.path(entry.getKey());String out=translated.isTextual()?translated.asString(""):"";
            validate(entry.getValue(),out);result.put(entry.getKey(),out);
        }
        return result;
    }
    static void validate(String source,String translated) {
        if(translated==null||translated.isBlank()||translated.length()>Math.max(2000,source.length()*12)||HAN.matcher(translated).find())throw new IllegalArgumentException("Incomplete translation");
        if(!faithfulNumbers(source,translated))throw new IllegalArgumentException("Changed numeric values");
        if(!URLS.matcher(source).results().map(r->r.group()).toList().equals(URLS.matcher(translated).results().map(r->r.group()).toList()))throw new IllegalArgumentException("Changed reference URLs");
        String normalized=translated.replaceAll("\\s+","").toLowerCase(Locale.ROOT);
        for(var unit:UNITS.matcher(source).results().map(r->r.group()).toList())if(!normalized.contains(unit.replaceAll("\\s+","").toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Changed units");
    }
    private static boolean faithfulNumbers(String source,String translated) {
        var actual=NUMBERS.matcher(translated).results().map(r->r.group()).toList();
        var positions=new HashSet<Integer>();positions.add(0);
        var matcher=SOURCE_NUMBERS.matcher(source);
        while(matcher.find()) {
            boolean required=matcher.group(1)!=null;
            String expected=required?matcher.group(1):sectionNumber(matcher.group(2));
            // Clinical Arabic numbers are mandatory. A Chinese list label may be a word or its corresponding digit.
            var next=required?new HashSet<Integer>():new HashSet<>(positions);
            for(int at:positions)if(at<actual.size()&&actual.get(at).equals(expected))next.add(at+1);
            positions=next;
        }
        return positions.contains(actual.size());
    }
    private static String sectionNumber(String value) {
        String digits="一二三四五六七八九";int ten=value.indexOf('十');
        if(ten<0)return value.length()==1?String.valueOf(digits.indexOf(value.charAt(0))+1):"";
        if(ten>1||value.length()>ten+2)return "";
        int tens=ten==0?1:digits.indexOf(value.charAt(0))+1;
        int ones=ten==value.length()-1?0:digits.indexOf(value.charAt(ten+1))+1;
        return tens>0&&ones>=0?String.valueOf(tens*10+ones):"";
    }
}
