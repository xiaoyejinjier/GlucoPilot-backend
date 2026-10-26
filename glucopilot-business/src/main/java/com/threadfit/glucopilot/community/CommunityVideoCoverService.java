package com.threadfit.glucopilot.community;

import com.threadfit.glucopilot.file.entity.MediaFile;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class CommunityVideoCoverService {
    private final CommunityArticleImageService images;
    private final VideoCoverRenderer renderer;
    // At most 64 * 512 KiB, with two simultaneous source downloads/decoders.
    private final Map<Key,byte[]> cache = new LinkedHashMap<>(64,.75f,true);
    private final Semaphore decoders = new Semaphore(2);
    private final Object[] locks = new Object[32];

    public CommunityVideoCoverService(CommunityArticleImageService images, VideoCoverRenderer renderer) {
        this.images = images; this.renderer = renderer;
        for (int i=0; i<locks.length; i++) locks[i] = new Object();
    }

    public byte[] read(long viewer, long mediaId) {
        var video = images.requireMedia(viewer,null,mediaId,true);
        var key = Key.of(video);
        synchronized (locks[(key.hashCode() & Integer.MAX_VALUE) % locks.length]) {
            var result = cached(key);
            if (result != null) return result;
            boolean acquired = false;
            try {
                acquired = decoders.tryAcquire(15, TimeUnit.SECONDS);
                if (!acquired) throw VideoCoverRenderer.unavailable();
                result = renderer.render(images.readAuthorized(video,true).bytes());
                if (result == null || result.length < 4 || result.length > VideoCoverRenderer.MAX_COVER_BYTES)
                    throw VideoCoverRenderer.unavailable();
                synchronized (cache) {
                    if (cache.size() >= 64) cache.remove(cache.keySet().iterator().next());
                    cache.put(key,result);
                }
                return result;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw VideoCoverRenderer.unavailable();
            } finally { if (acquired) decoders.release(); }
        }
    }

    private byte[] cached(Key key) { synchronized (cache) { return cache.get(key); } }
    private record Key(Long id, Integer provider, String bucket, String path, String hash, Long size) {
        static Key of(MediaFile file) {
            return new Key(file.getId(),file.getStorageProvider(),file.getBucketName(),file.getStorageKey(),file.getSha256Hex(),file.getSizeBytes());
        }
    }
}
