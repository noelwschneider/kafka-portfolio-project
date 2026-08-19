package com.orderfulfillment.scenario.web;

import com.orderfulfillment.scenario.admin.DemoResetService;
import com.orderfulfillment.scenario.dto.ResetResultDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/openapi/scenario-service.yaml — /demo/reset. */
@RestController
public class DemoAdminController {

    private final DemoResetService resetService;

    public DemoAdminController(DemoResetService resetService) {
        this.resetService = resetService;
    }

    @PostMapping("/demo/reset")
    public ResetResultDto reset() {
        return resetService.reset();
    }
}
