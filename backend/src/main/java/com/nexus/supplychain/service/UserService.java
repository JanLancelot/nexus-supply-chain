package com.nexus.supplychain.service;

import com.nexus.supplychain.dto.UserCreateRequest;
import com.nexus.supplychain.dto.UserResponse;
import com.nexus.supplychain.exception.BadRequestException;
import com.nexus.supplychain.model.Role;
import com.nexus.supplychain.model.User;
import com.nexus.supplychain.repository.RoleRepository;
import com.nexus.supplychain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public User getUserById(UUID id) {
        if (id == null) {
            return null;
        }
        return userRepository.findById(id).orElse(null);
    }

    public List<User> getUsersByRole(String roleName) {
        if (roleName == null) {
            return List.of();
        }
        return userRepository.findByRoleName(roleName);
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
                .role(user.getRole().getName())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
