package com.enterprise.invoice;

import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.enterprise.notification.NotificationService;
import com.enterprise.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InvoiceMessageService {

    private final InvoiceMessageRepository messageRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    public InvoiceMessageService(InvoiceMessageRepository messageRepository,
                                 NotificationService notificationService,
                                 UserRepository userRepository) {
        this.messageRepository = messageRepository;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    @Transactional
    public InvoiceMessage sendMessage(Invoice invoice, AppUser sender, String message) {
        InvoiceMessage msg = new InvoiceMessage(invoice, sender, message);
        msg = messageRepository.save(msg);

        boolean isMerchant = sender.getId().equals(invoice.getMerchantId());
        boolean isCustomer = invoice.getCustomerEmail().equalsIgnoreCase(sender.getEmail());
        boolean isAdmin = sender.getRoles().stream().anyMatch(r -> r.getName().equals("ADMIN"));

        // ✅ Tenant-scoped — previously "findByRolesName" returned every tenant's
        //    admins and could leak notification cross-tenant.
        Long tenantId = invoice.getTenantId() != null
                ? invoice.getTenantId() : TenantContext.getRequiredTenantId();
        List<AppUser> admins = userRepository.findByTenantIdAndRoleName(tenantId, "ADMIN");

        if (isMerchant) {
            admins.forEach(admin -> notificationService.createNotification(
                    admin.getId(), "CHAT_MESSAGE",
                    "New Chat Message from Merchant",
                    "Merchant sent a message about Invoice #" + invoice.getId(),
                    "/admin/invoices/" + invoice.getId() + "/messages"));
        } else if (isCustomer) {
            notificationService.createNotification(
                    invoice.getMerchantId(), "CHAT_MESSAGE",
                    "New Chat Message from Customer",
                    "Customer sent a message about Invoice #" + invoice.getId(),
                    "/invoices/" + invoice.getId() + "/messages");
            admins.forEach(admin -> notificationService.createNotification(
                    admin.getId(), "CHAT_MESSAGE",
                    "New Chat Message from Customer",
                    "Customer sent a message about Invoice #" + invoice.getId(),
                    "/admin/invoices/" + invoice.getId() + "/messages"));
        } else if (isAdmin) {
            notificationService.createNotification(
                    invoice.getMerchantId(), "CHAT_MESSAGE",
                    "New Chat Message from Admin",
                    "Admin replied about Invoice #" + invoice.getId(),
                    "/invoices/" + invoice.getId() + "/messages");
        }
        return msg;
    }

    public List<InvoiceMessage> getMessagesForInvoice(Long invoiceId) {
        return messageRepository.findByInvoiceIdOrderByCreatedAtAsc(invoiceId);
    }
}