package com.cinema.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Long 字段智能序列化: 超过 JS Number.MAX_SAFE_INTEGER (2^53-1) 的 ID 转 String,
 * 否则保持 number。这样后端可继续用雪花/自增 ID,前端不会丢精度。
 */
@Configuration
public class JacksonConfig {

    private static final long JS_MAX_SAFE_INTEGER = (1L << 53) - 1L;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(Long.class, new JsonSerializer<Long>() {
                @Override
                public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                    if (value == null) {
                        gen.writeNull();
                    } else if (Math.abs(value) > JS_MAX_SAFE_INTEGER) {
                        gen.writeString(value.toString());
                    } else {
                        gen.writeNumber(value);
                    }
                }
            });
            module.addSerializer(Long.TYPE, new JsonSerializer<Long>() {
                @Override
                public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                    if (value == null) {
                        gen.writeNull();
                    } else if (Math.abs(value) > JS_MAX_SAFE_INTEGER) {
                        gen.writeString(value.toString());
                    } else {
                        gen.writeNumber(value);
                    }
                }
            });
            builder.modulesToInstall(module);
        };
    }
}
