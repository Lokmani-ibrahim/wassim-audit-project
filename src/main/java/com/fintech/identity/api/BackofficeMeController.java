package com.fintech.identity.api;

import com.fintech.identity.security.StaffAccessPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/backoffice")
public class BackofficeMeController {

    private final StaffAccessPolicy staffAccessPolicy;

    public BackofficeMeController(StaffAccessPolicy staffAccessPolicy) {
        this.staffAccessPolicy = staffAccessPolicy;
    }

    @GetMapping("/me")
    public BackofficeMeResponse me(Authentication authentication) {
        return new BackofficeMeResponse(
                ((JwtAuthenticationToken) authentication).getToken().getSubject(),
                staffAccessPolicy.capabilities(authentication)
        );
    }

    public record BackofficeMeResponse(String staffSubject, List<String> capabilities) {
    }
}
