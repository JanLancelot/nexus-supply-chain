package com.nexus.supplychain.config;

import com.nexus.supplychain.dto.NotificationResponse;
import com.nexus.supplychain.dto.OrderCreateRequest;
import com.nexus.supplychain.dto.OrderItemRequest;
import com.nexus.supplychain.dto.ProductCreateRequest;
import com.nexus.supplychain.model.User;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DtoContractTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void inactiveProductRequestUsesTheFrontendPropertyName() {
        ProductCreateRequest request = mapper.readValue("{\"isActive\":false}", ProductCreateRequest.class);

        assertFalse(request.isActive());
        var serialized = mapper.readTree(mapper.writeValueAsString(request));
        assertTrue(serialized.has("isActive"));
        assertFalse(serialized.has("active"));
    }

    @Test
    void notificationReadStateUsesTheFrontendPropertyName() {
        NotificationResponse response = NotificationResponse.builder().isRead(true).build();

        var serialized = mapper.readTree(mapper.writeValueAsString(response));
        assertTrue(serialized.get("isRead").asBoolean());
        assertFalse(serialized.has("read"));
        assertTrue(mapper.readValue(mapper.writeValueAsString(response), NotificationResponse.class).isRead());
    }

    @Test
    void orderValidationRejectsOversizedAndNullItemLists() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            OrderItemRequest item = new OrderItemRequest(UUID.randomUUID(), 1);
            OrderCreateRequest oversized = new OrderCreateRequest(UUID.randomUUID(), UUID.randomUUID(),
                    new ArrayList<>(Collections.nCopies(501, item)));
            OrderCreateRequest nullItem = new OrderCreateRequest(UUID.randomUUID(), UUID.randomUUID(),
                    Collections.singletonList(null));

            assertFalse(validator.validate(oversized).isEmpty());
            assertFalse(validator.validate(nullItem).isEmpty());
        }
    }

    @Test
    void userSerializationNeverIncludesPasswordHashes() {
        User user = User.builder().email("user@example.com").passwordHash("password-hash").build();

        assertFalse(mapper.readTree(mapper.writeValueAsString(user)).has("passwordHash"));
    }
}
