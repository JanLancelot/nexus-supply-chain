package com.pg.supplychain.service;

import com.pg.supplychain.dto.WarehouseRequest;
import com.pg.supplychain.dto.WarehouseResponse;
import com.pg.supplychain.exception.ResourceNotFoundException;
import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import com.pg.supplychain.model.User;
import com.pg.supplychain.model.Warehouse;
import com.pg.supplychain.repository.UserRepository;
import com.pg.supplychain.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WarehouseServiceTest {

    @Mock private WarehouseRepository warehouseRepository;
    @Mock private UserRepository userRepository;
    @Mock private KafkaLiteBroker kafkaLiteBroker;

    @InjectMocks
    private WarehouseService warehouseService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testGetAllWarehouses() {
        Warehouse w = Warehouse.builder().id(UUID.randomUUID()).name("East").location("NY").build();
        when(warehouseRepository.findAll()).thenReturn(Collections.singletonList(w));

        List<WarehouseResponse> responses = warehouseService.getAllWarehouses();
        assertEquals(1, responses.size());
        assertEquals("East", responses.get(0).getName());
    }

    @Test
    void testGetWarehouseById() {
        UUID id = UUID.randomUUID();
        Warehouse w = Warehouse.builder().id(id).name("East").location("NY").build();
        when(warehouseRepository.findById(id)).thenReturn(Optional.of(w));

        WarehouseResponse response = warehouseService.getWarehouseById(id);
        assertNotNull(response);
        assertEquals("East", response.getName());
    }

    @Test
    void testGetWarehouseById_NotFound() {
        UUID id = UUID.randomUUID();
        when(warehouseRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> warehouseService.getWarehouseById(id));
    }

    @Test
    void testCreateWarehouse() {
        UUID managerId = UUID.randomUUID();
        User manager = User.builder().id(managerId).fullName("John Doe").build();
        WarehouseRequest request = new WarehouseRequest("West", "LA", managerId);

        when(userRepository.findById(managerId)).thenReturn(Optional.of(manager));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(i -> {
            Warehouse w = i.getArgument(0);
            w.setId(UUID.randomUUID());
            return w;
        });

        WarehouseResponse response = warehouseService.createWarehouse(request);
        assertNotNull(response);
        assertEquals("West", response.getName());
        assertEquals("John Doe", response.getManagerName());
        verify(kafkaLiteBroker, times(1)).send(eq("warehouse-events"), any());
    }

    @Test
    void testUpdateWarehouse() {
        UUID id = UUID.randomUUID();
        Warehouse existing = Warehouse.builder().id(id).name("Old").build();
        WarehouseRequest request = new WarehouseRequest("New", "LA", null);

        when(warehouseRepository.findById(id)).thenReturn(Optional.of(existing));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(i -> i.getArgument(0));

        WarehouseResponse response = warehouseService.updateWarehouse(id, request);
        assertNotNull(response);
        assertEquals("New", response.getName());
        verify(kafkaLiteBroker, times(1)).send(eq("warehouse-events"), any());
    }

    @Test
    void testDeleteWarehouse() {
        UUID id = UUID.randomUUID();
        Warehouse existing = Warehouse.builder().id(id).build();
        when(warehouseRepository.findById(id)).thenReturn(Optional.of(existing));

        warehouseService.deleteWarehouse(id);

        verify(warehouseRepository, times(1)).delete(existing);
        verify(kafkaLiteBroker, times(1)).send(eq("warehouse-events"), any());
    }
}
