package com.enterprise.api;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.invoice.Invoice;
import com.enterprise.invoice.InvoiceService;
import com.enterprise.invoice.PrivateMessage;
import com.enterprise.invoice.PrivateMessageService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/private-chat")
public class PrivateChatController {

    private final InvoiceService invoiceService;
    private final UserRepository userRepository;
    private final PrivateMessageService privateMessageService;

    public PrivateChatController(InvoiceService invoiceService,
                                 UserRepository userRepository,
                                 PrivateMessageService privateMessageService) {
        this.invoiceService = invoiceService;
        this.userRepository = userRepository;
        this.privateMessageService = privateMessageService;
    }

    private boolean isValidParticipant(AppUser u, Invoice invoice) {
        boolean isMerchant = u.getId().equals(invoice.getMerchantId());
        boolean isCustomer = invoice.getCustomerEmail().equals(u.getEmail());
        boolean isAdmin = u.getRoles().stream().anyMatch(r -> r.getName().equals("ADMIN"));
        return isMerchant || isCustomer || isAdmin;
    }

    @GetMapping("/{invoiceId}")
    public String chatPage(@PathVariable Long invoiceId,
                           @RequestParam(required = false) Long withUserId,
                           @RequestParam(required = false) String withEmail,
                           Authentication authentication,
                           Model model) {
        AppUser currentUser = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (!currentUser.isActive()) {
            model.addAttribute("error", "Your account has been disabled.");
            return "private-chat";
        }
        Invoice invoice = invoiceService.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));
        if (!isValidParticipant(currentUser, invoice)) {
            throw new RuntimeException("Unauthorized to view this chat");
        }

        AppUser otherUser;
        if (withUserId != null) {
            otherUser = userRepository.findById(withUserId)
                    .orElseThrow(() -> new RuntimeException("Other user not found"));
        } else if (withEmail != null) {
            otherUser = userRepository.findByEmail(withEmail)
                    .orElseThrow(() -> new RuntimeException("Other user not found"));
        } else {
            throw new RuntimeException("Missing participant identifier");
        }

        if (!otherUser.isActive()) {
            model.addAttribute("error", "The other user is disabled.");
            return "private-chat";
        }
        if (!isValidParticipant(otherUser, invoice)) {
            throw new RuntimeException("Invalid chat participant");
        }

        List<PrivateMessage> messages = privateMessageService
                .getMessagesBetweenUsers(invoiceId, currentUser.getId(), otherUser.getId());
        model.addAttribute("invoice", invoice);
        model.addAttribute("otherUser", otherUser);
        model.addAttribute("messages", messages);
        model.addAttribute("currentUserId", currentUser.getId());
        return "private-chat";
    }

    @PostMapping("/{invoiceId}/send")
    public String sendMessage(@PathVariable Long invoiceId,
                              @RequestParam Long recipientId,
                              @RequestParam String message,
                              Authentication authentication) {
        AppUser sender = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        Invoice invoice = invoiceService.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));
        AppUser recipient = userRepository.findById(recipientId)
                .orElseThrow(() -> new RuntimeException("Recipient not found"));

        if (!sender.isActive()) throw new RuntimeException("Your account is disabled.");
        if (!recipient.isActive()) throw new RuntimeException("Recipient is disabled.");

        // ✅ Both sender AND recipient must be valid participants on this invoice
        if (!isValidParticipant(sender, invoice)) {
            throw new RuntimeException("You are not a participant on this invoice");
        }
        if (!isValidParticipant(recipient, invoice)) {
            throw new RuntimeException("Recipient is not a participant on this invoice");
        }

        privateMessageService.sendPrivateMessage(invoice, sender, recipient, message);
        return "redirect:/private-chat/" + invoiceId + "?withUserId=" + recipientId;
    }
}