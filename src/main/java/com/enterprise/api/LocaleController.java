package com.enterprise.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.LocaleResolver;

import java.net.URI;
import java.util.Locale;

@Controller
public class LocaleController {

    private final LocaleResolver localeResolver;

    public LocaleController(LocaleResolver localeResolver) {
        this.localeResolver = localeResolver;
    }

    @GetMapping("/locale")
    public String changeLocale(@RequestParam String lang,
                               HttpServletRequest request,
                               HttpServletResponse response) {
        // ✅ Validate lang against supported locales — was raw new Locale(lang)
        String code = (lang == null ? "" : lang).trim().toLowerCase();
        if (!code.equals("en") && !code.equals("de") && !code.equals("fr")
                && !code.equals("es") && !code.equals("it")) {
            code = "en";
        }
        localeResolver.setLocale(request, response, new Locale(code));

        // ✅ Fix open redirect: only accept a Referer on the same host
        String referer = request.getHeader("Referer");
        if (referer != null) {
            try {
                URI ref = URI.create(referer);
                String requestHost = request.getServerName();
                if (ref.getHost() != null && ref.getHost().equalsIgnoreCase(requestHost)) {
                    return "redirect:" + ref.getPath()
                            + (ref.getQuery() != null ? "?" + ref.getQuery() : "");
                }
            } catch (Exception ignored) {}
        }
        return "redirect:/dashboard";
    }
}