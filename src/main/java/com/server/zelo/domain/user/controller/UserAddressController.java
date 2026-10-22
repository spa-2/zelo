package com.server.zelo.domain.user.controller;


import com.server.zelo.common.api.ApiResponse;
import com.server.zelo.common.auth.Auth;
import com.server.zelo.common.auth.AuthUser;
import com.server.zelo.domain.user.dto.request.CreateUserAddressRequest;
import com.server.zelo.domain.user.dto.request.UpdateUserAddressRequest;
import com.server.zelo.domain.user.dto.response.CreateUserAddressResponse;
import com.server.zelo.domain.user.dto.response.GetUserAddressResponse;
import com.server.zelo.domain.user.dto.response.UpdateUserAddressResponse;
import com.server.zelo.domain.user.service.UserAddressService;
import com.server.zelo.domain.user.service.UserFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users/me/addresses")
public class UserAddressController {

    private final UserFacade userFacade;
    private final UserAddressService userAddressService;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateUserAddressResponse>> createUserAddress(
            @Auth AuthUser authUser, @Valid @RequestBody CreateUserAddressRequest request) {
        CreateUserAddressResponse response = userFacade.createUserAddress(authUser.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<GetUserAddressResponse>>> getUserAddresses(@Auth AuthUser authUser) {
        List<GetUserAddressResponse> response = userAddressService.getUserAddresses(authUser.id());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{addressId}")
    public ResponseEntity<ApiResponse<UpdateUserAddressResponse>> updateUserAddress(
            @Auth AuthUser authUser, @PathVariable Long addressId,
            @Valid @RequestBody UpdateUserAddressRequest request) {
        UpdateUserAddressResponse response = userFacade.updateUserAddress(authUser.id(), addressId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }


}
