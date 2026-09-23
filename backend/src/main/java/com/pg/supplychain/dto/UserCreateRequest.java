package com.pg.supplychain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.AssertTrue;
import java.nio.charset.StandardCharsets;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserCreateRequest {

    @NotBlank(message = "Full name is required")
    @Size(max = 255, message = "Full name must not exceed 255 characters")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters long")
    @Size(max = 72, message = "Password must not exceed 72 characters")
    @ToString.Exclude
    private String password;

    @NotBlank(message = "Role is required")
    @jakarta.validation.constraints.Pattern(regexp = "ROLE_ADMIN|ROLE_STAFF", message = "Role must be ROLE_ADMIN or ROLE_STAFF")
    private String roleName;
    @AssertTrue(message = "Password must not exceed 72 UTF-8 bytes")
    public boolean isPasswordWithinByteLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
