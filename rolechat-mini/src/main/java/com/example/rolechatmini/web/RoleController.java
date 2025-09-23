package com.example.rolechatmini.web;

import com.example.rolechatmini.service.RoleCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleCatalogService roleCatalogService;

    @GetMapping("/search")
    public Object search(@RequestParam(required = false) String q) {
        return roleCatalogService.search(q);
    }
}
