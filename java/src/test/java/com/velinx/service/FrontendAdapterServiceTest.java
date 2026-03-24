package com.velinx.service;

import com.velinx.dto.frontendadapter.SessionStartRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FrontendAdapterServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("normalize keeps workPath instead of reusing profilePath")
    void normalize_KeepsWorkPath() {
        FrontendAdapterService service = new FrontendAdapterService();

        SessionStartRequest normalized = service.normalize(new SessionStartRequest(
                "AI",
                "1",
                false,
                "profile-path",
                "world-path",
                "  workspace-path  "
        ));

        assertEquals("workspace-path", normalized.workPath());
        assertEquals("profile-path", normalized.profilePath());
        assertEquals("world-path", normalized.worldPath());
    }

    @Test
    @DisplayName("workspaceDir prefers normalized workPath when provided")
    void resolveWorkspaceDir_PrefersNormalizedWorkPath() throws Exception {
        FrontendAdapterService service = new FrontendAdapterService();
        setConfiguredWorkspaceDir(service, tempDir.resolve("configured-workspace").toString());

        String clientWorkspace = tempDir.resolve("client-workspace").resolve("..").resolve("client-workspace").toString();
        SessionStartRequest normalized = service.normalize(new SessionStartRequest(
                "AI",
                "1",
                false,
                null,
                null,
                clientWorkspace
        ));

        assertEquals(Path.of(clientWorkspace).toAbsolutePath().normalize().toString(), service.resolveWorkspaceDir(normalized));
    }

    @Test
    @DisplayName("workspaceDir falls back to configured value when workPath is missing")
    void resolveWorkspaceDir_FallsBackToConfiguredWorkspaceDir() throws Exception {
        FrontendAdapterService service = new FrontendAdapterService();
        String configuredWorkspaceDir = tempDir.resolve("configured-workspace").toString();
        setConfiguredWorkspaceDir(service, configuredWorkspaceDir);

        SessionStartRequest normalized = service.normalize(new SessionStartRequest(
                "AI",
                "1",
                false,
                null,
                null,
                "   "
        ));

        assertNull(normalized.workPath());
        assertEquals(
                Path.of(configuredWorkspaceDir).toAbsolutePath().normalize().toString(),
                service.resolveWorkspaceDir(normalized)
        );
    }

    private void setConfiguredWorkspaceDir(FrontendAdapterService service, String value) throws Exception {
        Field field = FrontendAdapterService.class.getDeclaredField("configuredWorkspaceDir");
        field.setAccessible(true);
        field.set(service, value);
    }
}
