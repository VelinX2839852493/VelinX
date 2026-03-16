package com.velinx.core.skill;

import java.util.List;

/**
 * 技能管理器接口。
 * 负责发现本地技能、为当前轮次选择激活技能、生成提示词上下文，
 * 以及执行已经被选中的技能脚本。
 */
public interface SkillManager {

    /**
     * 返回所有已发现的技能。
     */
    List<Skill> listSkills();

    /**
     * 为当前用户输入准备技能上下文。
     * 只有当用户显式写出类似 `$web-search` 的技能标记时，技能才会被激活。
     */
    void prepareTurn(String userInput);

    /**
     * 返回当前轮次已选中的技能。
     */
    List<Skill> getActiveSkills();

    /**
     * 为当前已选中的技能生成提示词上下文。
     */
    String getActiveSkillPrompt();

    /**
     * 执行指定激活技能中的可运行脚本。
     *
     * @param skillName 当前轮次已激活技能的 ID 或显示名
     * @param scriptName 不带扩展名的脚本名
     * @param paramsJson 通过标准输入以 UTF-8 JSON 传递给脚本的参数
     * @return 脚本的标准输出；执行失败时返回错误信息
     */
    String executeSkill(String skillName, String scriptName, String paramsJson);
}