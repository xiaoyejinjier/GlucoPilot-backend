package com.threadfit.glucopilot.file.controller;

import com.threadfit.glucopilot.file.storage.LocalObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LocalObjectStorageControllerTest {

    private final LocalObjectStorageService storageService = mock(LocalObjectStorageService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new LocalObjectStorageController(storageService))
            .build();

    @Test
    void acceptsRawBinaryUpload() throws Exception {
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01};

        mockMvc.perform(put("/api/v1/media-files/local-uploads/upload-token")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(jpeg))
                .andExpect(status().isNoContent());

        verify(storageService).acceptUpload("upload-token", jpeg);
    }

    @Test
    void rejectsJsonContentTypeBeforeItCanCorruptBinaryBytes() throws Exception {
        mockMvc.perform(put("/api/v1/media-files/local-uploads/upload-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff}))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(storageService);
    }
}
