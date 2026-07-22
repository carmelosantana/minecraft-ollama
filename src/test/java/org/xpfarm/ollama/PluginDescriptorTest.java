package org.xpfarm.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses the shipped resource YAML with the same SnakeYAML the server uses.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A malformed {@code plugin.yml} is not a compile error, is not a test failure, and does not
 * fail {@code mvn verify} — Maven copies the file into the JAR and it is only parsed when a real
 * Paper server boots. Magic Carpet shipped a descriptor whose unquoted {@code ": "} inside the
 * description made SnakeYAML read the rest of the line as a nested mapping and throw
 * {@code ScannerException: mapping values are not allowed here}. Paper logged
 * {@code InvalidDescriptionException} and never registered the plugin at all — it was absent from
 * {@code /plugins} rather than present-and-disabled, a materially more confusing symptom. The
 * defect survived every per-task review, an adversarial whole-branch review, and a green CI run,
 * because nothing in the pipeline ever parsed the file as YAML.
 *
 * <p>These tests close that gap at gate 6 instead of gate 7a.
 */
final class PluginDescriptorTest {

    private static final Path PLUGIN_YML = descriptor("plugin.yml");
    private static final Path CONFIG_YML = descriptor("config.yml");

    /**
     * Prefers the Maven-filtered copy in {@code target/classes} — that is the file that actually
     * ships, and property substitution can inject YAML metacharacters the source file never had.
     * Falls back to the source tree so the test still runs before {@code process-resources}.
     */
    private static Path descriptor(String name) {
        Path filtered = Path.of("target", "classes", name);
        return Files.exists(filtered) ? filtered : Path.of("src", "main", "resources", name);
    }

    private static Map<String, Object> parse(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().load(in);
        }
    }

    @Test
    void pluginYmlIsValidYaml() throws IOException {
        assertNotNull(parse(PLUGIN_YML), "plugin.yml parsed to null — the file is empty or malformed");
    }

    @Test
    void configYmlIsValidYaml() throws IOException {
        assertNotNull(parse(CONFIG_YML), "config.yml parsed to null — the file is empty or malformed");
    }

    @Test
    void pluginYmlDeclaresTheFieldsPaperRequires() throws IOException {
        Map<String, Object> parsed = parse(PLUGIN_YML);

        assertEquals("Ollama", parsed.get("name"));
        assertEquals("org.xpfarm.ollama.OllamaPlugin", parsed.get("main"));
        assertInstanceOf(String.class, parsed.get("api-version"),
                "api-version must be quoted; unquoted it parses as a double and 1.20 becomes 1.2");
        assertEquals("1.21", parsed.get("api-version"));
        assertNotNull(parsed.get("description"), "description is required");

        Object version = parsed.get("version");
        assertNotNull(version, "version is required");
        assertFalse(version.toString().contains("${"),
                "version still holds an unresolved Maven property: " + version);
    }

    @Test
    void pluginYmlDeclaresEveryCommandTheCodeLooksUp() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> commands = (Map<String, Object>) parse(PLUGIN_YML).get("commands");
        assertNotNull(commands, "commands section is required");
        assertTrue(commands.containsKey("ollama"),
                "the ollama command must be declared or getCommand(\"ollama\") returns null");
    }

    @Test
    void pluginYmlDeclaresEveryPermissionTheCodeChecks() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> permissions = (Map<String, Object>) parse(PLUGIN_YML).get("permissions");
        assertNotNull(permissions, "permissions section is required");
        assertTrue(permissions.containsKey("ollama.admin"), "ollama.admin must be declared");
        assertTrue(permissions.containsKey("ollama.chat"), "ollama.chat must be declared");
        assertTrue(permissions.containsKey("ollama.code"), "ollama.code must be declared");
        assertTrue(permissions.containsKey("ollama.debug"), "ollama.debug must be declared");
        assertTrue(permissions.containsKey("ollama.generate"), "ollama.generate must be declared");
        assertTrue(permissions.containsKey("ollama.run"), "ollama.run must be declared");
    }

    @Test
    void pluginYmlDeclaresItsSoftDependencies() throws IOException {
        Object softdepend = parse(PLUGIN_YML).get("softdepend");
        assertNotNull(softdepend, "softdepend is required");
        String declared = softdepend.toString();
        assertTrue(declared.contains("ViaVersion"), "ViaVersion must be a soft dependency");
    }
}
