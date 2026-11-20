package com.threadfit.glucopilot.device.cloud;

import com.threadfit.glucopilot.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenCryptoTest {

    @Test
    void roundTripWithConfiguredKey() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        TokenCrypto crypto = new TokenCrypto(key);
        assertTrue(crypto.isReady());
        String cipher = crypto.encrypt("access-token-plain");
        assertFalse(cipher.contains("access-token-plain"));
        assertEquals("access-token-plain", crypto.decrypt(cipher));
    }

    @Test
    void missingKeyRejectsEncrypt() {
        TokenCrypto crypto = new TokenCrypto("");
        assertFalse(crypto.isReady());
        assertThrows(BizException.class, () -> crypto.encrypt("x"));
    }
}
