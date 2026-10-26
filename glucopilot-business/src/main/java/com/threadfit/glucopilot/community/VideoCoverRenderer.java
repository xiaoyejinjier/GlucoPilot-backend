package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Extract a small still from local bytes; never pass user URLs or options to a shell. */
@Component
public class VideoCoverRenderer {
    static final int MAX_COVER_BYTES = 512 * 1024;
    private final String executable;

    public VideoCoverRenderer(@Value("${FFMPEG_PATH:ffmpeg}") String executable) {
        this.executable = executable;
    }

    byte[] render(byte[] video) {
        Path directory = null, input = null, output = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory("glucopilot-cover-");
            input = directory.resolve("source.mp4");
            output = directory.resolve("cover.jpg");
            Files.write(input, video);
            process = new ProcessBuilder(executable, "-nostdin", "-hide_banner", "-loglevel", "error",
                    "-protocol_whitelist", "file,pipe", "-max_alloc", "67108864", "-threads", "1", "-filter_threads", "1",
                    "-f", "mov", "-i", input.toString(), "-map", "0:v:0", "-frames:v", "1",
                    "-vf", "scale=480:480:force_original_aspect_ratio=decrease",
                    "-q:v", "4", "-fs", Integer.toString(MAX_COVER_BYTES), "-y", output.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(12, TimeUnit.SECONDS) || process.exitValue() != 0
                    || !Files.isRegularFile(output) || Files.size(output) > MAX_COVER_BYTES) throw unavailable();
            byte[] jpeg = Files.readAllBytes(output);
            if (jpeg.length < 4 || (jpeg[0] & 255) != 255 || (jpeg[1] & 255) != 216) throw unavailable();
            var image = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1
                    || image.getWidth() > 480 || image.getHeight() > 480) throw unavailable();
            return jpeg;
        } catch (IOException failure) {
            throw unavailable();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                boolean interrupted = Thread.interrupted();
                try { process.waitFor(2, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt();
            }
            delete(output); delete(input); delete(directory);
        }
    }

    private static void delete(Path path) {
        if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) { /* OS cleanup may retry. */ }
    }

    static BizException unavailable() {
        return new BizException(ErrorCode.BIZ_REJECTED, "视频封面暂时无法生成，请稍后重试");
    }
}
