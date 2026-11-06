package com.enterprise.api;

import com.enterprise.currency.CurrencyCodes;
import com.enterprise.currency.ExchangeRate;
import com.enterprise.currency.ExchangeRateService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

/**
 * ✅ Exchange rates are GLOBAL (ExchangeRate has no tenantId).
 *    A MERCHANT_ADMIN editing rates would affect every tenant.
 *    Restricted to SUPER_ADMIN.
 */
@Controller
@RequestMapping("/admin/exchange-rates")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    public AdminExchangeRateController(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    @GetMapping
    public String listRates(Model model) {
        model.addAttribute("rates", exchangeRateService.getAllRates());
        return "admin/exchange-rates/list";
    }

    @GetMapping("/create")
    public String showCreateForm(Model model) {
        model.addAttribute("rate", new ExchangeRate());
        model.addAttribute("currencies", CurrencyCodes.getAllCurrencies());
        return "admin/exchange-rates/form";
    }

    @PostMapping
    public String createRate(@RequestParam String fromCurrency,
                             @RequestParam String toCurrency,
                             @RequestParam BigDecimal rate,
                             RedirectAttributes redirectAttributes) {
        try {
            exchangeRateService.updateRate(fromCurrency, toCurrency, rate);
            redirectAttributes.addFlashAttribute("success",
                    "Exchange rate " + fromCurrency + " → " + toCurrency + " created.");
        } catch (DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("error",
                    "Rate already exists. Use Edit instead.");
            return "redirect:/admin/exchange-rates/create";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed: " + e.getMessage());
            return "redirect:/admin/exchange-rates/create";
        }
        return "redirect:/admin/exchange-rates";
    }

    @GetMapping("/{id}/edit")
    public String showEditForm(@PathVariable Long id, Model model) {
        ExchangeRate rate = exchangeRateService.getRate(id)
                .orElseThrow(() -> new RuntimeException("Rate not found"));
        model.addAttribute("rate", rate);
        model.addAttribute("currencies", CurrencyCodes.getAllCurrencies());
        return "admin/exchange-rates/form";
    }

    @PostMapping("/{id}")
    public String updateRate(@PathVariable Long id,
                             @RequestParam BigDecimal rate,
                             RedirectAttributes redirectAttributes) {
        ExchangeRate existing = exchangeRateService.getRate(id)
                .orElseThrow(() -> new RuntimeException("Rate not found"));
        try {
            exchangeRateService.updateRate(existing.getFromCurrency(), existing.getToCurrency(), rate);
            redirectAttributes.addFlashAttribute("success", "Rate updated.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed: " + e.getMessage());
            return "redirect:/admin/exchange-rates/" + id + "/edit";
        }
        return "redirect:/admin/exchange-rates";
    }

    @PostMapping("/{id}/delete")
    public String deleteRate(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            exchangeRateService.deleteRate(id);
            redirectAttributes.addFlashAttribute("success", "Rate deleted.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed: " + e.getMessage());
        }
        return "redirect:/admin/exchange-rates";
    }
}