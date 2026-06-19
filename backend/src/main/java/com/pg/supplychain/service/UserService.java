package com.pg.supplychain.service;

import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final Map<UUID, User> userCache = new ConcurrentHashMap<>();
    private final Map<String, List<User>> roleCache = new ConcurrentHashMap<>();

    public User getUserById(UUID id) {
        if (id == null) {
            return null;
        }
        return userCache.computeIfAbsent(id, key -> userRepository.findById(key).orElse(null));
    }

    public List<User> getUsersByRole(String roleName) {
        if (roleName == null) {
            return List.of();
        }
        return roleCache.computeIfAbsent(roleName, key -> userRepository.findByRoleName(key));
    }
}
