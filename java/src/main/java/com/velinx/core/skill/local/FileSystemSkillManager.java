package com.velinx.core.skill.local;

import com.velinx.core.skill.Skill;
import com.velinx.core.skill.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class FileSystemSkillManager implements SkillManager {

    private static final Logger logger = LoggerFactory.getLogger(FileSystemSkillManager.class);
    private static final Pattern SKILL_MARKER_PATTERN = Pattern.compile("\\$([\\p{L}\\p{N}_-]+)");
    private static final List<String> INSTRUCTION_FILE_NAMES = List.of("SKILL.md", "skill.md");
    private static final List<String> WINDOWS_SCRIPT_EXTENSIONS = List.of(".cmd", ".bat", ".ps1", ".py");
    private static final List<String> UNIX_SCRIPT_EXTENSIONS = List.of(".sh", ".py");

    private final Path skillsRoot;
    private final Path workspaceRoot;
    private final Consumer<String> actionCallback;
    private final Map<String, FileSkill> skillsById;
    private volatile List<Skill> activeSkills = List.of();

    public FileSystemSkillManager(String skillsDir, String workspaceDir, Consumer<String> actionCallback) {
        this.skillsRoot = normalizeDirectory(skillsDir);
        this.workspaceRoot = normalizeDirectory(workspaceDir);
        this.actionCallback = actionCallback;
        this.skillsById = Collections.unmodifiableMap(loadSkills());
    }

    @Override
    public List<Skill> listSkills() {
        return List.copyOf(skillsById.values());
    }

    @Override
    public void prepareTurn(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            activeSkills = List.of();
            return;
        }

        Set<String> requestedNames = extractSkillMarkers(userInput);
        if (requestedNames.isEmpty()) {
            activeSkills = List.of();
            return;
        }

        List<Skill> selectedSkills = new ArrayList<>();
        for (String requestedName : requestedNames) {
            FileSkill skill = resolveSkill(requestedName);
            if (skill != null) {
                selectedSkills.add(skill);
            } else {
                logger.debug("Ignored unknown skill marker: {}", requestedName);
            }
        }

        activeSkills = List.copyOf(selectedSkills);
        if (!selectedSkills.isEmpty() && actionCallback != null) {
            actionCallback.accept("Activated skills: " + selectedSkills.stream().map(Skill::getId).toList());
        }
    }

    @Override
    public List<Skill> getActiveSkills() {
        return activeSkills;
    }

    @Override
    public String getActiveSkillPrompt() {
        List<Skill> selectedSkills = activeSkills;
        if (selectedSkills.isEmpty()) {
            return "";
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("[Active Skills]\n");
        prompt.append("The user explicitly activated the following skills with $skill-name markers.\n");
        prompt.append("Follow the instruction for each active skill when it is relevant to the request.\n\n");

        for (Skill skill : selectedSkills) {
            prompt.append("- $")
                    .append(skill.getId())
                    .append(" (")
                    .append(skill.getName())
                    .append("): ")
                    .append(skill.getDescription())
                    .append("\n");
        }

        prompt.append("\n");
        for (Skill skill : selectedSkills) {
            prompt.append("[Skill: ")
                    .append(skill.getId())
                    .append("]\n")
                    .append(skill.getInstruction().trim())
                    .append("\n\n");
        }

        return prompt.toString().trim();
    }

    @Override
    public String executeSkill(String skillName, String scriptName, String paramsJson) {
        FileSkill skill = resolveActiveSkill(skillName);
        if (skill == null) {
            return "Skill not active: " + skillName;
        }

        Path scriptPath = resolveScriptPath(skill, scriptName);
        if (scriptPath == null) {
            return "Script not found for skill " + skill.getId() + ": " + scriptName;
        }

        try {
            Process process = new ProcessBuilder(buildCommand(scriptPath))
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true)
                    .start();

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                if (paramsJson != null && !paramsJson.isBlank()) {
                    writer.write(paramsJson);
                }
            }

            String output;
            try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                output = readAll(reader);
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return "Skill script exited with code " + exitCode + ": " + output.trim();
            }
            return output.trim();
        } catch (Exception e) {
            logger.warn("Failed to execute skill {} script {}", skill.getId(), scriptName, e);
            return "Skill execution failed: " + e.getMessage();
        }
    }

    private Map<String, FileSkill> loadSkills() {
        if (!Files.isDirectory(skillsRoot)) {
            logger.info("Skills directory does not exist: {}", skillsRoot);
            return Map.of();
        }

        Map<String, FileSkill> discovered = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.list(skillsRoot)) {
            stream.filter(Files::isDirectory)
                    .sorted()
                    .map(this::readSkill)
                    .filter(Objects::nonNull)
                    .forEach(skill -> discovered.put(normalizeKey(skill.getId()), skill));
        } catch (IOException e) {
            logger.warn("Failed to scan skills directory: {}", skillsRoot, e);
        }
        return discovered;
    }

    private FileSkill readSkill(Path skillDir) {
        Path instructionPath = findInstructionFile(skillDir);
        if (instructionPath == null) {
            return null;
        }

        try {
            String raw = Files.readString(instructionPath, StandardCharsets.UTF_8);
            FrontMatter frontMatter = parseFrontMatter(raw);
            String id = skillDir.getFileName().toString();
            String name = frontMatter.values.getOrDefault("name", id);
            String description = frontMatter.values.getOrDefault("description", "");
            List<String> triggers = parseTriggers(frontMatter.values.get("triggers"));
            List<String> scriptNames = discoverScripts(skillDir.resolve("scripts"));
            String instruction = frontMatter.body.isBlank() ? raw : frontMatter.body;

            return new FileSkill(id, name, description, triggers, scriptNames, instruction, skillDir);
        } catch (IOException e) {
            logger.warn("Failed to load skill from {}", instructionPath, e);
            return null;
        }
    }

    private Path findInstructionFile(Path skillDir) {
        for (String fileName : INSTRUCTION_FILE_NAMES) {
            Path candidate = skillDir.resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private List<String> discoverScripts(Path scriptsDir) {
        if (!Files.isDirectory(scriptsDir)) {
            return List.of();
        }

        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.list(scriptsDir)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .map(this::stripExtension)
                    .forEach(names::add);
        } catch (IOException e) {
            logger.warn("Failed to inspect scripts for {}", scriptsDir, e);
        }
        return List.copyOf(names);
    }

    private Set<String> extractSkillMarkers(String userInput) {
        Matcher matcher = SKILL_MARKER_PATTERN.matcher(userInput);
        Set<String> markers = new LinkedHashSet<>();
        while (matcher.find()) {
            markers.add(normalizeKey(matcher.group(1)));
        }
        return markers;
    }

    private FileSkill resolveSkill(String requestedName) {
        FileSkill byId = skillsById.get(normalizeKey(requestedName));
        if (byId != null) {
            return byId;
        }

        for (FileSkill skill : skillsById.values()) {
            if (normalizeKey(skill.getName()).equals(normalizeKey(requestedName))) {
                return skill;
            }
        }
        return null;
    }

    private FileSkill resolveActiveSkill(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return null;
        }

        String normalized = normalizeKey(stripDollarPrefix(skillName));
        for (Skill skill : activeSkills) {
            if (normalizeKey(skill.getId()).equals(normalized) || normalizeKey(skill.getName()).equals(normalized)) {
                return (FileSkill) skill;
            }
        }
        return null;
    }

    private Path resolveScriptPath(FileSkill skill, String scriptName) {
        if (scriptName == null || scriptName.isBlank()) {
            return null;
        }

        Path scriptsDir = skill.getRoot().resolve("scripts");
        List<String> extensions = isWindows() ? WINDOWS_SCRIPT_EXTENSIONS : UNIX_SCRIPT_EXTENSIONS;
        for (String extension : extensions) {
            Path candidate = scriptsDir.resolve(scriptName + extension);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }

        try (Stream<Path> stream = Files.list(scriptsDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> stripExtension(path.getFileName().toString()).equals(scriptName))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            logger.warn("Failed to resolve script {} for skill {}", scriptName, skill.getId(), e);
            return null;
        }
    }

    private List<String> buildCommand(Path scriptPath) {
        String fileName = scriptPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".py")) {
            return List.of("python", scriptPath.toString());
        }
        if (fileName.endsWith(".ps1")) {
            return List.of("powershell", "-ExecutionPolicy", "Bypass", "-File", scriptPath.toString());
        }
        if (isWindows() && (fileName.endsWith(".cmd") || fileName.endsWith(".bat"))) {
            return List.of("cmd", "/c", scriptPath.toString());
        }
        if (!isWindows() && fileName.endsWith(".sh")) {
            return List.of("sh", scriptPath.toString());
        }
        return List.of(scriptPath.toString());
    }

    private FrontMatter parseFrontMatter(String raw) {
        if (!raw.startsWith("---")) {
            return new FrontMatter(Map.of(), raw);
        }

        int secondFence = raw.indexOf("\n---", 3);
        if (secondFence < 0) {
            return new FrontMatter(Map.of(), raw);
        }

        String frontMatterBlock = raw.substring(3, secondFence).trim();
        String body = raw.substring(secondFence + 4).trim();

        Map<String, String> values = new LinkedHashMap<>();
        for (String line : frontMatterBlock.split("\\R")) {
            int separatorIndex = line.indexOf(':');
            if (separatorIndex <= 0) {
                continue;
            }
            String key = line.substring(0, separatorIndex).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separatorIndex + 1).trim();
            values.put(key, value);
        }
        return new FrontMatter(values, body);
    }

    private List<String> parseTriggers(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private Path normalizeDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        return Path.of(directory).toAbsolutePath().normalize();
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        return stripDollarPrefix(value).trim().toLowerCase(Locale.ROOT);
    }

    private String stripDollarPrefix(String value) {
        return value != null && value.startsWith("$") ? value.substring(1) : value;
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private String readAll(Reader reader) throws IOException {
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[2048];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            builder.append(buffer, 0, read);
        }
        return builder.toString();
    }

    private record FrontMatter(Map<String, String> values, String body) {
    }
}
