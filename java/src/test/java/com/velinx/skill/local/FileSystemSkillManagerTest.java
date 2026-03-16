package com.velinx.skill.local;

import com.velinx.core.skill.Skill;
import com.velinx.core.skill.SkillManager;
import com.velinx.core.skill.local.FileSystemSkillManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemSkillManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSelectActiveSkillsOnlyWhenUserUsesExplicitSkillMarker() throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);

        createSampleSkill(skillsRoot, "web-search");

        SkillManager manager = new FileSystemSkillManager(skillsRoot.toString(), workspaceRoot.toString(), null);
        manager.prepareTurn("please search the latest web news");
        assertEquals(0, manager.getActiveSkills().size());

        manager.prepareTurn("please use $web-search for the latest web news");

        assertEquals(1, manager.listSkills().size());
        assertEquals(1, manager.getActiveSkills().size());

        Skill skill = manager.getActiveSkills().getFirst();
        assertEquals("web-search", skill.getId());
        assertEquals("Web Search", skill.getName());
        assertTrue(skill.getScriptNames().contains("runner"));
        assertTrue(manager.getActiveSkillPrompt().contains("[Active Skills]"));
        assertTrue(manager.getActiveSkillPrompt().contains("$skill-name"));
        assertTrue(manager.getActiveSkillPrompt().contains("web-search"));
    }

    @Test
    void shouldOnlyExecuteActiveSkillScript() throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);

        createSampleSkill(skillsRoot, "demo-skill");

        SkillManager manager = new FileSystemSkillManager(skillsRoot.toString(), workspaceRoot.toString(), null);
        String rejected = manager.executeSkill("demo-skill", "runner", "{\"ok\":true}");
        assertTrue(rejected.contains("not active"));

        manager.prepareTurn("run $demo-skill now");
        String result = manager.executeSkill("demo-skill", "runner", "{\"ok\":true}");

        assertFalse(result.isBlank());
        assertTrue(result.contains("skill-ok"));
    }

    @Test
    void shouldLoadLowercaseSkillMarkdownFiles() throws Exception {
        Path skillsRoot = tempDir.resolve("skills");
        Path workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);

        Path skillRoot = skillsRoot.resolve("code");
        Files.createDirectories(skillRoot);
        Files.writeString(skillRoot.resolve("skill.md"), """
                ---
                name: Code Analyzer
                description: Analyze code.
                ---

                # Code Analyzer

                Use this skill for code analysis.
                """);

        SkillManager manager = new FileSystemSkillManager(skillsRoot.toString(), workspaceRoot.toString(), null);
        manager.prepareTurn("please use $code");

        assertEquals(1, manager.listSkills().size());
        assertEquals(1, manager.getActiveSkills().size());
        assertTrue(manager.getActiveSkillPrompt().contains("Code Analyzer"));
    }

    private void createSampleSkill(Path skillsRoot, String skillId) throws Exception {
        Path skillRoot = skillsRoot.resolve(skillId);
        Path scriptsRoot = skillRoot.resolve("scripts");
        Files.createDirectories(scriptsRoot);

        Files.writeString(skillRoot.resolve("SKILL.md"), """
                ---
                name: Web Search
                description: Search the web and fetch pages.
                triggers: search, web, latest, demo
                ---

                # Web Search

                Use this skill when the user asks for online information.
                """);

        if (isWindows()) {
            Files.writeString(scriptsRoot.resolve("runner.cmd"), """
                    @echo off
                    echo skill-ok
                    """);
        } else {
            Files.writeString(scriptsRoot.resolve("runner.sh"), """
                    #!/bin/sh
                    echo skill-ok
                    """);
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
