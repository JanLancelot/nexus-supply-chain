package com.pg.supplychain.service;

import com.pg.supplychain.dto.UserCreateRequest;
import com.pg.supplychain.dto.UserResponse;
import com.pg.supplychain.exception.BadRequestException;
import com.pg.supplychain.model.Role;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.RoleRepository;
import com.pg.supplychain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

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

    public UserResponse createUser(UserCreateRequest request) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new BadRequestException("Email is already registered");
        }

        Role role = roleRepository.findByName(request.getRoleName())
                .orElseThrow(() -> new BadRequestException("Role not found: " + request.getRoleName()));

        User user = User.builder()
                .fullName(request.getFullName())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(role)
                .status("ACTIVE")
                .build();

        User savedUser = userRepository.save(user);

        // Update caches: cache the user details, invalidate the role list to fetch updated users
        userCache.put(savedUser.getId(), savedUser);
        roleCache.remove(role.getName());

        // Publish audit event
        auditService.logChange(
                "User",
                savedUser.getId(),
                "CREATE",
                null,
                Map.of(
                        "fullName", savedUser.getFullName(),
                        "email", savedUser.getEmail(),
                        "role", role.getName(),
                        "status", savedUser.getStatus()
                )
        );

        return mapToResponse(savedUser);
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private UserResponse mapToResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .roleName(user.getRole().getName())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
