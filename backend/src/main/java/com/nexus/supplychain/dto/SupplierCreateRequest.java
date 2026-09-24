package com.nexus.supplychain.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor(onConstructor_ = @JsonCreator(mode = JsonCreator.Mode.DISABLED))
@Builder
public class SupplierCreateRequest {
    @NotBlank
    @Size(max = 255)
    private String name;
    @Size(max = 255)
    private String contactPerson;
    @Email
    @Size(max = 255)
    private String email;
    @Size(max = 50)
    private String phone;
    @Size(max = 255)
    private String address;
    @NotNull
    @Min(0)
    @Max(3650)
    @Builder.Default
    private Integer leadTimeDays = 3;
    @Builder.Default
    private boolean isActive = true;
    @NotNull
    @Size(max = 500)
    @Builder.Default
    private List<@NotNull UUID> productIds = new ArrayList<>();

    @JsonProperty("isActive")
    public boolean isActive() {
        return isActive;
    }

    @JsonProperty("isActive")
    public void setActive(boolean active) {
        this.isActive = active;
    }
}
