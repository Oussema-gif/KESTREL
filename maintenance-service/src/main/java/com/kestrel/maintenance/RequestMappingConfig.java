package com.kestrel.maintenance;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RequestMappingConfig {
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Bean
    Jackson2ObjectMapperBuilderCustomizer uuidDeserializer() {
        return builder -> builder.deserializerByType(UUID.class, new StdScalarDeserializer<UUID>(UUID.class) {
            @Override
            public UUID deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                String value = parser.getValueAsString();
                if (value == null || !UUID_PATTERN.matcher(value).matches()) {
                    return context.reportInputMismatch(UUID.class, "Expected a canonical UUID string.");
                }
                return UUID.fromString(value);
            }
        });
    }

    static UUID uuid(String value) {
        if (value == null) {
            return null;
        }
        if (!UUID_PATTERN.matcher(value).matches()) {
            throw ApiProblem.badRequest("Expected a canonical UUID string.");
        }
        return UUID.fromString(value);
    }
}
