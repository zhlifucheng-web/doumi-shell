package com.doumiot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;

public final class ApplicationVersion {
    private ApplicationVersion() { }
    public static String browserUrl(String root) {
        try (InputStream in = ApplicationVersion.class.getResourceAsStream("/static/version.json")) {
            if (in != null) {
                String version = new ObjectMapper().readTree(in).path("version").asText();
                if (version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) return root + "?v=" + version;
            }
        } catch (Exception ignored) { /* Development without built frontend. */ }
        return root;
    }
}
