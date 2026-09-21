package com.example.Ece.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DeepSeekPropertiesTest {

    @Test
    void defaultsAreSafeWhenNoEnvironmentOverridesExist() {
        DeepSeekProperties properties = new DeepSeekProperties();

        assertEquals("https://api.deepseek.com", properties.getBaseUrl());
        assertEquals("deepseek-flash", properties.getModel());
        assertFalse(properties.hasApiKey());
    }
}
