package com.yanban.api.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.api.security.JwtProperties;
import org.junit.jupiter.api.Test;

class SettingsCryptoServiceTest {

    @Test
    void encryptAndDecryptRoundTrip() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test_secret_123456789012345678901234567890");
        SettingsCryptoService service = new SettingsCryptoService(properties);

        String encrypted = service.encrypt("sk-secret");

        assertThat(encrypted).isNotEqualTo("sk-secret");
        assertThat(service.decrypt(encrypted)).isEqualTo("sk-secret");
    }
}
