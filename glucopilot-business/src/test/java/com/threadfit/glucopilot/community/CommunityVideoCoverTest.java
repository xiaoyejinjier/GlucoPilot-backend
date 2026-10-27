package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.file.entity.MediaFile;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class CommunityVideoCoverTest {
    private final CommunityArticleImageService images = mock(CommunityArticleImageService.class);
    private final VideoCoverRenderer renderer = mock(VideoCoverRenderer.class);
    private final CommunityVideoCoverService service = new CommunityVideoCoverService(images,renderer);
    private final byte[] source = {1,2,3,4}, jpeg = {(byte)255,(byte)216,(byte)255,(byte)217};

    private MediaFile video(long id) {
        var file = new MediaFile(); file.setId(id); file.setStorageProvider(1); file.setBucketName("test");
        file.setStorageKey("video/"+id+".mp4"); file.setSizeBytes(4L); file.setSha256Hex("source-v1");
        when(images.requireMedia(2L,null,id,true)).thenReturn(file);
        when(images.readAuthorized(file,true)).thenReturn(new CommunityArticleImageService.Image(source,"video/mp4"));
        when(renderer.render(source)).thenReturn(jpeg);
        return file;
    }

    @Test void cachedFrameStillRequiresCurrentReadingPermissionAndActiveUser() {
        var file = video(7);
        assertArrayEquals(jpeg,service.read(2,7)); assertArrayEquals(jpeg,service.read(2,7));
        verify(images,times(2)).requireMedia(2L,null,7,true);
        verify(images,times(1)).readAuthorized(file,true); verify(renderer,times(1)).render(source);
        when(images.requireMedia(2L,null,7,true)).thenThrow(new BizException(ErrorCode.NOT_FOUND));
        assertThrows(BizException.class,()->service.read(2,7));
        when(images.requireMedia(3L,null,7,true)).thenThrow(new BizException(ErrorCode.NOT_FOUND));
        assertThrows(BizException.class,()->service.read(3,7));
        verify(renderer,times(1)).render(source);
    }

    @Test void sourceIdentityChangesInvalidateCacheAndFailedFramesAreRetryable() {
        var file = video(7); service.read(2,7);
        file.setStorageKey("video/replaced.mp4"); file.setSha256Hex("source-v2");
        when(renderer.render(source)).thenThrow(VideoCoverRenderer.unavailable()).thenReturn(jpeg);
        assertThrows(BizException.class,()->service.read(2,7));
        assertArrayEquals(jpeg,service.read(2,7)); verify(renderer,times(3)).render(source);
    }

    @Test void cacheEvictsOldFramesInsteadOfGrowingWithoutLimit() {
        for (long id=1; id<=65; id++) { video(id); service.read(2,id); }
        service.read(2,65); verify(renderer,times(65)).render(source);
        service.read(2,1); verify(renderer,times(66)).render(source);
    }

    @Test void simultaneousRequestsForTheSameVideoShareOneDecode() throws Exception {
        video(7);
        try (var pool=Executors.newFixedThreadPool(4)) {
            var first=pool.submit(()->service.read(2,7)); var second=pool.submit(()->service.read(2,7));
            assertArrayEquals(jpeg,first.get(5,TimeUnit.SECONDS)); assertArrayEquals(jpeg,second.get(5,TimeUnit.SECONDS));
        }
        verify(renderer,times(1)).render(source); verify(images,times(2)).requireMedia(2L,null,7,true);
    }

    @Test void coverHttpRequiresCIdentityAndIsPrivateJpeg() throws Exception {
        var covers=mock(CommunityVideoCoverService.class); var controller=new CommunityVideoCoverController(covers);
        RequestContext.clear(); assertThrows(BizException.class,()->controller.cover(7)); verifyNoInteractions(covers);
        try {
            RequestContext.setUserId(2L); when(covers.read(2,7)).thenReturn(jpeg);
            var response=MockMvcBuilders.standaloneSetup(controller).build().perform(get("/api/v1/community/article-videos/7/cover")).andReturn().getResponse();
            assertEquals(200,response.getStatus()); assertEquals("image/jpeg",response.getContentType());
            assertEquals("no-store",response.getHeader("Cache-Control")); assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));
            assertArrayEquals(jpeg,response.getContentAsByteArray()); verify(covers).read(2,7);
        } finally { RequestContext.clear(); }
    }
}
