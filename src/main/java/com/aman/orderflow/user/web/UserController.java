package com.aman.orderflow.user.web;

import com.aman.orderflow.common.exception.ResourceNotFoundException;
import com.aman.orderflow.security.UserPrincipal;
import com.aman.orderflow.user.dto.UserResponse;
import com.aman.orderflow.user.repository.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        var user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + principal.getId()));
        return UserResponse.from(user);
    }
}
