package com.enterprise.api;

import com.enterprise.plan.PlatformPlan;
import com.enterprise.plan.PlatformPlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class LandingController {

    private static final Logger log = LoggerFactory.getLogger(LandingController.class);

    private final PlatformPlanRepository planRepository;
    private final ObjectMapper objectMapper;

    public LandingController(PlatformPlanRepository planRepository,
                             ObjectMapper objectMapper) {
        this.planRepository = planRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/")
    public String landing(Model model) {
        List<PlatformPlan> plans = planRepository.findByActiveTrueOrderByDisplayOrderAsc();
        model.addAttribute("plans", plans);
        model.addAttribute("plansJson", toJson(plans));
        return "landing/index";
    }

    @GetMapping("/pricing")
    public String pricing(Model model) {
        List<PlatformPlan> plans = planRepository.findByActiveTrueOrderByDisplayOrderAsc();
        model.addAttribute("plans", plans);
        return "landing/pricing";
    }

    private String toJson(List<PlatformPlan> plans) {
        try {
            return objectMapper.writeValueAsString(plans);
        } catch (Exception e) {
            log.warn("Failed to serialize plans for landing page: {}", e.getMessage());
            return "[]";
        }
    }
}