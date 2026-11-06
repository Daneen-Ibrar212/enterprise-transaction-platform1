package com.enterprise.api;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Controller
@RequestMapping("/notifications")
public class NotificationSSEController {

    private static final Logger log = LoggerFactory.getLogger(NotificationSSEController.class);

    /** SSE timeout — 30 min, then the client reconnects. */
    private static final long SSE_TIMEOUT_MS = 30L * 60 * 1000;

    /** Maximum concurrent emitters across all users — protects the heap. */
    private static final int MAX_TOTAL_EMITTERS = 2000;

    private static final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    private final NotificationService notificationService;
    private final UserRepository userRepository;

    public NotificationSSEController(NotificationService notificationService,
                                     UserRepository userRepository) {
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    @GetMapping("/stream")
    @Transactional(readOnly = true)
    public SseEmitter stream(Authentication authentication) {
        if (emitters.size() >= MAX_TOTAL_EMITTERS) {
            log.warn("SSE emitter limit reached ({}), rejecting new connection", MAX_TOTAL_EMITTERS);
            throw new RuntimeException("Notification service at capacity — please retry later");
        }

        String email = authentication.getName();
        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Long userId = user.getId();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // Replace any previous emitter for this user
        SseEmitter prior = emitters.put(userId, emitter);
        if (prior != null) {
            try { prior.complete(); } catch (Exception ignored) {}
        }

        emitter.onCompletion(() -> { emitters.remove(userId, emitter); });
        emitter.onTimeout(() -> { emitters.remove(userId, emitter); emitter.complete(); });
        emitter.onError(e -> { emitters.remove(userId, emitter); });

        try {
            long count = notificationService.countUnread(userId);
            emitter.send(SseEmitter.event()
                    .name("notificationCount")
                    .data(String.valueOf(count)));
        } catch (IOException e) {
            emitters.remove(userId, emitter);
        }
        return emitter;
    }

    public static void broadcast(Long userId, long count) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name("notificationCount")
                        .data(String.valueOf(count)));
            } catch (IOException e) {
                emitters.remove(userId, emitter);
                log.debug("Removed stale emitter for user {}", userId);
            }
        }
    }
}