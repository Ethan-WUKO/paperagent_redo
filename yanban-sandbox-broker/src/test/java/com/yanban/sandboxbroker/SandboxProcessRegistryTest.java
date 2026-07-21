package com.yanban.sandboxbroker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SandboxProcessRegistryTest {
    @Test
    void cancellationDoesNotInterruptProviderCreateButCleanupStillCan() {
        SandboxProcessRegistry registry = new SandboxProcessRegistry();
        RecordingProcess create = new RecordingProcess();
        registry.register("execution", create, false);

        registry.cancel("execution");
        assertThat(create.destroyed).isFalse();

        registry.terminate("execution");
        assertThat(create.destroyed).isTrue();
    }

    @Test
    void cancellationStillInterruptsRunningUserCommand() {
        SandboxProcessRegistry registry = new SandboxProcessRegistry();
        RecordingProcess command = new RecordingProcess();
        registry.register("execution", command, true);

        registry.cancel("execution");

        assertThat(command.destroyed).isTrue();
    }

    private static final class RecordingProcess extends Process {
        private boolean destroyed;
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { destroyed = true; }
    }
}
