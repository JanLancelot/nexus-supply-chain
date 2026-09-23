package com.pg.supplychain.controller;

import com.pg.supplychain.dto.ApiError;
import com.pg.supplychain.dto.LoginRequest;
import com.pg.supplychain.dto.LoginResponse;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.UserRepository;
import com.pg.supplychain.security.JwtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.UUID;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    // Keep a BCrypt comparison on unknown accounts to avoid a fast account-enumeration path.
    private static final String DUMMY_PASSWORD_HASH = new BCryptPasswordEncoder(10).encode(UUID.randomUUID().toString());

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Optional<User> userOpt = userRepository.findByEmail(request.getEmail());

        String passwordHash = userOpt.map(User::getPasswordHash).orElse(DUMMY_PASSWORD_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), passwordHash);
        if (userOpt.isEmpty() || !passwordMatches || !"ACTIVE".equals(userOpt.get().getStatus())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError.builder()
                    .status(HttpStatus.UNAUTHORIZED.value())
                    .error("Unauthorized")
                    .message("Invalid email or password")
                    .build());
        }

        User user = userOpt.get();
        String token = jwtService.generateToken(user.getEmail(), user.getRole().getName(), user.getId().toString(), user.getFullName());

        return ResponseEntity.ok(new LoginResponse(token));
    }
}
