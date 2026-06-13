package com.pg.supplychain.controller;

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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Optional<User> userOpt = userRepository.findByEmail(request.getEmail());

        if (userOpt.isEmpty() || !passwordEncoder.matches(request.getPassword(), userOpt.get().getPasswordHash())) {
            Map<String, Object> errorDetails = new HashMap<>();
            errorDetails.put("status", HttpStatus.UNAUTHORIZED.value());
            errorDetails.put("error", "Unauthorized");
            errorDetails.put("message", "Invalid email or password");
            errorDetails.put("timestamp", java.time.OffsetDateTime.now().toString());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorDetails);
        }

        User user = userOpt.get();
        String token = jwtService.generateToken(user.getEmail(), user.getRole().name(), user.getId().toString());

        return ResponseEntity.ok(new LoginResponse(token));
    }
}
