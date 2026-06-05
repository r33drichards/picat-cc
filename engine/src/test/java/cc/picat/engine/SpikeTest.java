package cc.picat.engine;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SpikeTest {
    @Test
    void helloWorldRunsUnderChicory() {
        PicatRunner.RawResult r = PicatRunner.runRaw(
            Map.of("/work/main.pi", "main => println(hello).\n"),
            java.util.List.of("picat", "/work/main.pi"),
            30_000);
        assertEquals("hello", r.stdout().trim(),
            "stderr was: " + r.stderr());
        assertEquals(0, r.exitCode());
    }
}
