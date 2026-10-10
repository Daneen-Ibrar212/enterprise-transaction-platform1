package com.enterprise.reporting;

import com.enterprise.currency.CurrencySymbols;
import com.enterprise.tenant.TenantContext;
import com.enterprise.transaction.Transaction;
import com.enterprise.transaction.TransactionRepository;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class CustomerStatementService {

    private static final Logger log = LoggerFactory.getLogger(CustomerStatementService.class);

    private final TransactionRepository transactionRepository;
    private final CustomerReceiptRepository receiptRepository;

    public CustomerStatementService(TransactionRepository transactionRepository,
                                    CustomerReceiptRepository receiptRepository) {
        this.transactionRepository = transactionRepository;
        this.receiptRepository = receiptRepository;
    }

    /**
     * Generate + persist a combined statement PDF for a customer.
     */
    @Transactional
    public CustomerReceipt generateStatement(Long userId, String customerEmail,
                                             LocalDate startDate, LocalDate endDate) {
        Long tenantId = TenantContext.getRequiredTenantId();

        List<Transaction> transactions = transactionRepository
                .findByCustomerIdOrderByCreatedAtDesc(userId)
                .stream()
                .filter(tx -> {
                    LocalDate txDate = tx.getCreatedAt().toLocalDate();
                    return !txDate.isBefore(startDate) && !txDate.isAfter(endDate);
                })
                .toList();

        byte[] pdfBytes = buildStatementPdf(customerEmail, transactions, startDate, endDate);

        CustomerReceipt receipt = new CustomerReceipt();
        receipt.setUserId(userId);
        receipt.setTenantId(tenantId);
        receipt.setStartDate(startDate);
        receipt.setEndDate(endDate);
        receipt.setContentType("application/pdf");
        receipt.setContent(pdfBytes);
        receipt.setFilename(String.format("statement_%s_to_%s.pdf", startDate, endDate));

        CustomerReceipt saved = receiptRepository.save(receipt);
        log.info("✅ Statement generated for user {} ({} tx, {} bytes)",
                userId, transactions.size(), pdfBytes.length);
        return saved;
    }

    public List<CustomerReceipt> listForUser(Long userId) {
        return receiptRepository.findByUserIdOrderByGeneratedAtDesc(userId);
    }

    public CustomerReceipt getReceipt(Long id, Long userId) {
        CustomerReceipt receipt = receiptRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Receipt not found"));
        if (!receipt.getUserId().equals(userId)) {
            throw new SecurityException("You do not own this statement");
        }
        return receipt;
    }

    // ============================================================
    // PDF BUILDER
    // ============================================================
    private byte[] buildStatementPdf(String customerEmail,
                                     List<Transaction> transactions,
                                     LocalDate startDate,
                                     LocalDate endDate) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = new PdfWriter(out);
            PdfDocument pdf = new PdfDocument(writer);
            Document doc = new Document(pdf);

            PdfFont bold = PdfFontFactory.createFont("Helvetica-Bold");
            PdfFont regular = PdfFontFactory.createFont("Helvetica");
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");

            doc.add(new Paragraph("Account Statement")
                    .setFont(bold).setFontSize(20)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(6));

            doc.add(new Paragraph(customerEmail)
                    .setFont(regular).setFontSize(11)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(4));

            doc.add(new Paragraph(startDate.format(fmt) + "  —  " + endDate.format(fmt))
                    .setFont(regular).setFontSize(10)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(20));

            // Table
            Table table = new Table(UnitValue.createPercentArray(new float[]{12, 12, 36, 20, 20}));
            table.setWidth(UnitValue.createPercentValue(100));

            String[] headers = {"Date", "Invoice", "Description", "Amount", "Status"};
            for (String h : headers) {
                table.addHeaderCell(new Cell().add(new Paragraph(h).setFont(bold).setFontSize(9)));
            }

            BigDecimal total = BigDecimal.ZERO;
            String currency = "GBP";

            for (Transaction tx : transactions) {
                table.addCell(new Cell().add(new Paragraph(
                        tx.getCreatedAt().toLocalDate().format(fmt)).setFont(regular).setFontSize(9)));
                table.addCell(new Cell().add(new Paragraph(
                        "#" + tx.getInvoiceId()).setFont(regular).setFontSize(9)));
                table.addCell(new Cell().add(new Paragraph(
                        "Invoice #" + tx.getInvoiceId()).setFont(regular).setFontSize(9)));
                table.addCell(new Cell().add(new Paragraph(
                        CurrencySymbols.getSymbol(tx.getCurrency()) + " " + tx.getAmount())
                        .setFont(regular).setFontSize(9)));
                table.addCell(new Cell().add(new Paragraph(
                        tx.getStatus().name()).setFont(regular).setFontSize(9)));

                total = total.add(tx.getAmount());
                currency = tx.getCurrency();
            }

            doc.add(table);

            doc.add(new Paragraph("\n"));

            doc.add(new Paragraph("Total: " + CurrencySymbols.getSymbol(currency) + " " + total)
                    .setFont(bold).setFontSize(13)
                    .setTextAlignment(TextAlignment.RIGHT));

            doc.add(new Paragraph("\n\nPayment received — thank you!")
                    .setFont(regular).setFontSize(10)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(30));

            doc.close();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate statement PDF", e);
        }
        return out.toByteArray();
    }
}