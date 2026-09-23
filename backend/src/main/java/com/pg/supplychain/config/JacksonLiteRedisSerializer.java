package com.pg.supplychain.config;

import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.util.regex.Pattern;

public class JacksonLiteRedisSerializer implements RedisSerializer<Object> {
    
    private final ObjectMapper objectMapper;

    public JacksonLiteRedisSerializer(ObjectMapper objectMapper) {
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                // Cache bytes must never select arbitrary classes from the application classpath.
                .allowIfSubType(Pattern.compile("com\\.pg\\.supplychain\\.dto\\.(ProductResponse|PagedResponse|CategoryResponse|WarehouseResponse|AnalyticsDashboardResponse(\\$TopProduct)?)"))
                .allowIfSubType(Pattern.compile("java\\.util\\.(ArrayList|HashMap|LinkedHashMap)"))
                .allowIfSubType(Pattern.compile("java\\.math\\.(BigDecimal|BigInteger)|java\\.lang\\.(Long|Integer|Double|Float|Boolean|String)"))
                .build();
        this.objectMapper = objectMapper.rebuild()
                .activateDefaultTyping(ptv, DefaultTyping.NON_FINAL, com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY)
                .configure(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
                .configure(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
    }

    @Override
    public byte[] serialize(Object t) throws SerializationException {
        if (t == null) {
            return new byte[0];
        }
        try {
            return objectMapper.writeValueAsBytes(t);
        } catch (Exception e) {
            throw new SerializationException("Could not write JSON: " + e.getMessage(), e);
        }
    }

    @Override
    public Object deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return objectMapper.readValue(bytes, Object.class);
        } catch (Exception e) {
            throw new SerializationException("Could not read JSON: " + e.getMessage(), e);
        }
    }
}
