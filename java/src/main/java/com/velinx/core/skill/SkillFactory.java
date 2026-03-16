package com.velinx.core.skill;

import com.velinx.core.skill.local.FileSystemSkillManager;

import java.util.function.Consumer;

public final class SkillFactory {

    private SkillFactory() {
    }

    public static SkillManager create(String skillsDir, String workspaceDir, Consumer<String> actionCallback) {
        return new FileSystemSkillManager(skillsDir, workspaceDir, actionCallback);
    }
}
