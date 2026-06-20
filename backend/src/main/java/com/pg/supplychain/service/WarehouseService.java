package com.pg.supplychain.service;

import com.pg.supplychain.dto.WarehouseRequest;
import com.pg.supplychain.dto.WarehouseResponse;
import com.pg.supplychain.exception.ResourceNotFoundException;
import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import com.pg.supplychain.kafkalite.event.WarehouseEvent;
import com.pg.supplychain.model.User;
import com.pg.supplychain.model.Warehouse;
import com.pg.supplychain.repository.UserRepository;
import com.pg.supplychain.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;
    private final KafkaLiteBroker kafkaLiteBroker;

    @Transactional(readOnly = true)
    @Cacheable(value = "warehouses", key = "'all'", sync = true)
    public List<WarehouseResponse> getAllWarehouses() {
        return warehouseRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "warehouses", key = "#id", sync = true)
    public WarehouseResponse getWarehouseById(UUID id) {
        Warehouse warehouse = warehouseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with ID: " + id));
        return mapToResponse(warehouse);
    }

    @Transactional
    public WarehouseResponse createWarehouse(WarehouseRequest request) {
        User manager = null;
        if (request.getManagerId() != null) {
            manager = userRepository.findById(request.getManagerId())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + request.getManagerId()));
        }

        Warehouse warehouse = Warehouse.builder()
                .name(request.getName())
                .location(request.getLocation())
                .manager(manager)
                .build();

        Warehouse saved = warehouseRepository.save(warehouse);

        // Publish event to kafka-lite
        kafkaLiteBroker.send("warehouse-events", new WarehouseEvent(saved.getId(), "CREATED"));

        return mapToResponse(saved);
    }

    @Transactional
    public WarehouseResponse updateWarehouse(UUID id, WarehouseRequest request) {
        Warehouse warehouse = warehouseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with ID: " + id));

        User manager = null;
        if (request.getManagerId() != null) {
            manager = userRepository.findById(request.getManagerId())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + request.getManagerId()));
        }

        warehouse.setName(request.getName());
        warehouse.setLocation(request.getLocation());
        warehouse.setManager(manager);

        Warehouse updated = warehouseRepository.save(warehouse);

        // Publish event to kafka-lite
        kafkaLiteBroker.send("warehouse-events", new WarehouseEvent(updated.getId(), "UPDATED"));

        return mapToResponse(updated);
    }

    @Transactional
    public void deleteWarehouse(UUID id) {
        Warehouse warehouse = warehouseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with ID: " + id));
        warehouseRepository.delete(warehouse);

        // Publish event to kafka-lite
        kafkaLiteBroker.send("warehouse-events", new WarehouseEvent(id, "DELETED"));
    }

    private WarehouseResponse mapToResponse(Warehouse warehouse) {
        return WarehouseResponse.builder()
                .id(warehouse.getId())
                .name(warehouse.getName())
                .location(warehouse.getLocation())
                .managerId(warehouse.getManager() != null ? warehouse.getManager().getId() : null)
                .managerName(warehouse.getManager() != null ? warehouse.getManager().getFullName() : null)
                .build();
    }
}
