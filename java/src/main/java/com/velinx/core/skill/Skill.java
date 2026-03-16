package com.velinx.core.skill;

import java.util.List;

/**
 * 技能契约接口。
 * 一个 skill 表示从本地技能目录中发现的可复用能力单元。
 */
public interface Skill {

    /**
     * 返回稳定的技能标识。
     * 通常使用技能目录名，运行时会用它来路由技能调用。
     */
    String getId();

    /**
     * 返回面向人的技能名称。
     */
    String getName();

    /**
     * 返回可注入提示词的简短描述。
     */
    String getDescription();

    /**
     * 返回技能原始声明中的触发词列表。
     * 当前运行时不直接依赖它激活技能，但会作为技能元数据保留。
     */
    List<String> getTriggers();

    /**
     * 返回技能暴露出来的可执行脚本名称。
     * 这里的名称不包含文件扩展名。
     */
    List<String> getScriptNames();

    /**
     * 返回技能完整的 Markdown 指令正文。
     */
    String getInstruction();
}