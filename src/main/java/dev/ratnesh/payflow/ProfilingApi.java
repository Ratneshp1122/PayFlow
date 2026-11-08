package dev.ratnesh.payflow;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import jdk.jfr.Recording;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ProfilingApi {
  private final ProfilingDataSource ds;
  private final boolean enabled;
  private Recording recording;

  public ProfilingApi(
      ProfilingDataSource ds, @Value("${payflow.profiling:false}") boolean enabled) {
    this.ds = ds;
    this.enabled = enabled;
  }

  private void check() {
    if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  @GetMapping("/ops/profile/statements")
  Map<String, String> statements() {
    check();
    return ds.catalog();
  }

  @PostMapping("/ops/profile/start")
  synchronized void start() {
    check();
    if (recording != null) throw new ResponseStatusException(HttpStatus.CONFLICT);
    recording = new Recording();
    recording.setMaxSize(64 * 1024 * 1024);
    recording.setMaxAge(Duration.ofMinutes(15));
    // Explicit allowlist: no environment variables, secrets, request bodies, or system properties.
    recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(20)).withStackTrace();
    recording.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(20)).withStackTrace();
    recording.enable("jdk.CPULoad").withPeriod(Duration.ofSeconds(1));
    recording.enable("jdk.GarbageCollection");
    recording.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(20)).withStackTrace();
    recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(20)).withStackTrace();
    recording.start();
  }

  @PostMapping("/ops/profile/stop")
  synchronized Map<String, String> stop() throws Exception {
    check();
    if (recording == null) throw new ResponseStatusException(HttpStatus.CONFLICT);
    try {
      recording.stop();
      recording.dump(Path.of("/tmp/payflow-profile.jfr"));
      return Map.of("path", "/tmp/payflow-profile.jfr");
    } finally {
      recording.close();
      recording = null;
    }
  }
}
