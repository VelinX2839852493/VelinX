package com.velinx.core.skill.local;

import com.velinx.core.skill.Skill;

import java.nio.file.Path;
import java.util.List;

final class FileSkill implements Skill {

    private final String id;
    private final String name;
    private final String description;
    private final List<String> triggers;
    private final List<String> scriptNames;
    private final String instruction;
    private final Path root;

    FileSkill(String id,
              String name,
              String description,
              List<String> triggers,
              List<String> scriptNames,
              String instruction,
              Path root) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.triggers = List.copyOf(triggers);
        this.scriptNames = List.copyOf(scriptNames);
        this.instruction = instruction;
        this.root = root;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public List<String> getTriggers() {
        return triggers;
    }

    @Override
    public List<String> getScriptNames() {
        return scriptNames;
    }

    @Override
    public String getInstruction() {
        return instruction;
    }

    Path getRoot() {
        return root;
    }
}
