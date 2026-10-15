package com.doumiot.t2.util;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class JsonUtil {

    public static final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .setFieldNamingPolicy(FieldNamingPolicy.IDENTITY)
            .serializeSpecialFloatingPointValues()
            .setFieldNamingStrategy(field -> underscoreToCamel(field.getName()))
            .enableComplexMapKeySerialization()
            .create();

    private JsonUtil() {
    }

    public static String toJson(Object obj) {
        return gson.toJson(obj);
    }

    public static String toUnderscoreJson(Collection<?> obj) {
        String json = gson.toJson(obj);
        json = convertCamelToSnake(json);
        log.info("toUnderscoreJson:{} ", json);
        return json;
    }

    private static String convertCamelToSnake(String jsonString) {
        Pattern pattern = Pattern.compile("\"([a-zA-Z0-9]+)\":");
        Matcher matcher = pattern.matcher(jsonString);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String camelCaseKey = matcher.group(1);
            String snakeCaseKey = camelCaseKey.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
            matcher.appendReplacement(sb, "\"" + snakeCaseKey + "\":");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static <T> T fromJsonObj(String json, Class<T> classOfT) {
        return gson.fromJson(json, classOfT);
    }

    public static String underscoreToCamel(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char ch = str.charAt(i);
            if (ch == '_') {
                i++;
                if (i < str.length()) {
                    ch = str.charAt(i);
                    result.append(Character.toUpperCase(ch));
                }
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    public static String camelToUnderscore(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        StringBuilder result = new StringBuilder();
        result.append(str.charAt(0));
        for (int i = 1; i < str.length(); i++) {
            char ch = str.charAt(i);
            if (Character.isUpperCase(ch)) {
                result.append('_');
                result.append(Character.toLowerCase(ch));
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }
}
