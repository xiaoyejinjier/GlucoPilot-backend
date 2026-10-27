package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VideoCoverRendererTest {
    @TempDir Path directory;

    @Test void missingExecutableFailsClearlyAndCleansTemporarySource() throws Exception {
        var before=temporaryCovers();
        assertThrows(BizException.class,()->new VideoCoverRenderer(directory.resolve("missing-ffmpeg").toString()).render(new byte[]{1,2,3}));
        assertEquals(before,temporaryCovers());
    }

    @Test void realFfmpegExtractsScaledPortraitFrameAndRejectsNonMp4WithoutLeavingFiles() throws Exception {
        String executable=System.getProperty("video.cover.ffmpeg",System.getenv("FFMPEG_PATH"));
        assumeTrue(executable!=null && Files.isRegularFile(Path.of(executable)),"Set video.cover.ffmpeg for real FFmpeg verification");
        var input=directory.resolve("portrait.mp4");
        var process=new ProcessBuilder(executable,"-nostdin","-hide_banner","-loglevel","error","-f","lavfi","-i","color=c=blue:s=720x1280:d=0.2","-c:v","libx264","-pix_fmt","yuv420p","-y",input.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try { assertTrue(process.waitFor(15,TimeUnit.SECONDS)); assertEquals(0,process.exitValue()); }
        finally { if(process.isAlive()) process.destroyForcibly(); }
        var renderer=new VideoCoverRenderer(executable); var before=temporaryCovers();
        var jpeg=renderer.render(Files.readAllBytes(input)); var image=ImageIO.read(new ByteArrayInputStream(jpeg));
        assertEquals(480,image.getHeight()); assertEquals(270,image.getWidth());
        assertTrue((image.getRGB(100,100)&255)>180,"JPEG must contain the actual blue video frame");
        assertTrue(jpeg.length<VideoCoverRenderer.MAX_COVER_BYTES); assertEquals(before,temporaryCovers());
        assertThrows(BizException.class,()->renderer.render("#EXTM3U\nfile:///not-an-mp4".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(before,temporaryCovers());
    }

    private Set<Path> temporaryCovers() throws Exception {
        try(var files=Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p->p.getFileName().toString().startsWith("glucopilot-cover-")).collect(Collectors.toSet());
        }
    }
}
