package com.enterprise.invoice;

import com.enterprise.currency.CurrencySymbols;
import com.enterprise.identity.AppUser;
import com.enterprise.identity.UserRepository;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
public class InvoicePdfService {

    private static final Logger log = LoggerFactory.getLogger(InvoicePdfService.class);
    private final UserRepository userRepository;

    // ============================================================
    // BRAND PALETTE — matches PayFlow web design system
    // ============================================================
    private static final Color INK           = new Color(15, 23, 42);       // #0f172a — dark text
    private static final Color NAVY          = new Color(11, 26, 51);       // #0b1a33 — hero navy
    private static final Color NAVY_2        = new Color(22, 52, 110);      // #16346e — mid navy
    private static final Color BLUE          = new Color(45, 107, 184);     // #2d6bb8 — primary blue
    private static final Color BLUE_SOFT     = new Color(234, 241, 255);    // #eaf1ff — soft blue
    private static final Color MINT          = new Color(16, 185, 129);     // #10b981 — success
    private static final Color MINT_SOFT     = new Color(209, 250, 229);    // #d1fae5 — soft mint
    private static final Color AMBER         = new Color(217, 119, 6);      // #d97706 — warning
    private static final Color AMBER_SOFT    = new Color(254, 243, 199);    // #fef3c7 — soft amber
    private static final Color ROSE          = new Color(185, 28, 28);      // #b91c1c — danger
    private static final Color ROSE_SOFT     = new Color(254, 226, 226);    // #fee2e2 — soft rose
    private static final Color PURPLE        = new Color(91, 33, 182);      // #5b21b6 — refunded
    private static final Color PURPLE_SOFT   = new Color(237, 233, 254);    // #ede9fe — soft purple
    private static final Color SLATE         = new Color(71, 85, 105);      // #475569 — secondary text
    private static final Color SLATE_MUTED   = new Color(100, 116, 139);    // #64748b — muted text
    private static final Color LIGHT_BG      = new Color(248, 250, 252);    // #f8fafc — light gray
    private static final Color CARD_BG       = new Color(241, 245, 249);    // #f1f5f9 — card bg
    private static final Color BORDER        = new Color(226, 232, 240);    // #e2e8f0 — border gray

    // ============================================================
    // FONT — We try to load DejaVu Sans for full Unicode currency
    // symbol support (₦, ₹, ₩, ₪, ₽, ฿, ₫, etc.). If unavailable,
    // we fall back to built-in Helvetica (Latin-1 only).
    // ============================================================
    private static final String UNICODE_FONT_PATH = "src/main/resources/fonts/DejaVuSans.ttf";
    private static final String UNICODE_FONT_BOLD_PATH = "src/main/resources/fonts/DejaVuSans-Bold.ttf";

    private static BaseFont BASE_REGULAR;
    private static BaseFont BASE_BOLD;
    private static boolean UNICODE_AVAILABLE = false;

    static {
        try {
            BASE_REGULAR = BaseFont.createFont(
                UNICODE_FONT_PATH, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            BASE_BOLD = BaseFont.createFont(
                UNICODE_FONT_BOLD_PATH, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            UNICODE_AVAILABLE = true;
        } catch (Exception e) {
            // Fonts not present — silently fall back to Helvetica
            UNICODE_AVAILABLE = false;
        }
    }

    public InvoicePdfService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // ============================================================
    // FONT FACTORY — returns appropriate font based on availability
    // ============================================================
    private Font makeFont(int size, int style, Color color) {
        if (UNICODE_AVAILABLE) {
            // With IDENTITY_H fonts, weight is baked into the TTF file.
            // We pick regular vs bold base font and use Font.NORMAL style.
            BaseFont base = (style == Font.BOLD) ? BASE_BOLD : BASE_REGULAR;
            return new Font(base, size, Font.NORMAL, color);
        } else {
            return new Font(Font.HELVETICA, size, style, color);
        }
    }

    // ============================================================
    // MAIN ENTRY POINT
    // ============================================================
    public byte[] generateInvoicePdf(Invoice invoice) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            Document document = new Document(PageSize.A4, 40, 40, 40, 40);
            PdfWriter writer = PdfWriter.getInstance(document, out);
            document.open();

            // Fonts — match Lexend Deca weights (300 normal / 500 bold → NORMAL / BOLD)
            Font brandFont    = makeFont(22, Font.BOLD,   NAVY);
            Font taglineFont  = makeFont(8,  Font.NORMAL, new Color(200, 214, 235));
            Font invoiceTitle = makeFont(30, Font.BOLD,   Color.WHITE);
            Font labelFont    = makeFont(8,  Font.BOLD,   SLATE_MUTED);
            Font valueFont    = makeFont(10, Font.NORMAL, INK);
            Font boldValue    = makeFont(10, Font.BOLD,   INK);
            Font largeBold    = makeFont(13, Font.BOLD,   INK);
            Font totalFont    = makeFont(18, Font.BOLD,   NAVY);

            // ============================================================
            // HERO HEADER (dark navy band, matching web gradient)
            // ============================================================
            PdfPTable heroTable = new PdfPTable(2);
            heroTable.setWidthPercentage(100);
            heroTable.setWidths(new float[]{3, 2});

            // Left: brand
            PdfPCell brandCell = new PdfPCell();
            brandCell.setBorder(Rectangle.NO_BORDER);
            brandCell.setPadding(24);
            brandCell.setPaddingBottom(20);
            brandCell.setBackgroundColor(NAVY);

            Paragraph brand = new Paragraph("PayFlow", brandFont);
            brandCell.addElement(brand);

            Paragraph tagline = new Paragraph("Enterprise Transaction Platform", taglineFont);
            tagline.setSpacingBefore(4);
            brandCell.addElement(tagline);

            heroTable.addCell(brandCell);

            // Right: INVOICE title
            PdfPCell badgeCell = new PdfPCell();
            badgeCell.setBorder(Rectangle.NO_BORDER);
            badgeCell.setPadding(24);
            badgeCell.setPaddingBottom(20);
            badgeCell.setBackgroundColor(NAVY);
            badgeCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            badgeCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

            Paragraph invoiceTitleP = new Paragraph("INVOICE", invoiceTitle);
            invoiceTitleP.setAlignment(Element.ALIGN_RIGHT);
            badgeCell.addElement(invoiceTitleP);

            heroTable.addCell(badgeCell);

            PdfPTable heroWrap = new PdfPTable(1);
            heroWrap.setWidthPercentage(100);
            PdfPCell heroWrapperCell = new PdfPCell(heroTable);
            heroWrapperCell.setBorder(Rectangle.NO_BORDER);
            heroWrapperCell.setPadding(0);
            heroWrap.addCell(heroWrapperCell);
            document.add(heroWrap);

            document.add(Chunk.NEWLINE);
            document.add(Chunk.NEWLINE);

            // ============================================================
            // INFO SECTION — Invoice details + Merchant block
            // ============================================================
            PdfPTable infoTable = new PdfPTable(2);
            infoTable.setWidthPercentage(100);
            infoTable.setWidths(new float[]{1, 1});

            // LEFT: Invoice details
            PdfPCell invoiceInfoCell = new PdfPCell();
            invoiceInfoCell.setBorder(Rectangle.NO_BORDER);
            invoiceInfoCell.setPaddingRight(15);

            Paragraph invoiceNumLabel = new Paragraph("INVOICE NUMBER", labelFont);
            invoiceInfoCell.addElement(invoiceNumLabel);
            Paragraph invoiceNumValue = new Paragraph("#" + invoice.getId(), largeBold);
            invoiceNumValue.setSpacingBefore(3);
            invoiceInfoCell.addElement(invoiceNumValue);
            invoiceInfoCell.addElement(Chunk.NEWLINE);

            Paragraph issueDateLabel = new Paragraph("ISSUE DATE", labelFont);
            invoiceInfoCell.addElement(issueDateLabel);
            Paragraph issueDateValue = new Paragraph(
                invoice.getCreatedAt().format(DateTimeFormatter.ofPattern("dd MMMM yyyy")),
                valueFont);
            issueDateValue.setSpacingBefore(3);
            invoiceInfoCell.addElement(issueDateValue);
            invoiceInfoCell.addElement(Chunk.NEWLINE);

            Paragraph statusLabel = new Paragraph("STATUS", labelFont);
            invoiceInfoCell.addElement(statusLabel);

            // Status pill (single-cell table with colored background)
            PdfPTable statusPillTable = new PdfPTable(1);
            statusPillTable.setWidthPercentage(40);
            statusPillTable.setHorizontalAlignment(Element.ALIGN_LEFT);
            PdfPCell statusPillCell = createStatusPillCell(invoice.getStatus());
            statusPillTable.addCell(statusPillCell);
            invoiceInfoCell.addElement(statusPillTable);

            infoTable.addCell(invoiceInfoCell);

            // RIGHT: Merchant / Customer card
            PdfPCell cardCell = new PdfPCell();
            cardCell.setBorder(Rectangle.NO_BORDER);
            cardCell.setBackgroundColor(CARD_BG);
            cardCell.setPadding(18);

            PdfPTable partyTable = new PdfPTable(1);
            partyTable.setWidthPercentage(100);

            PdfPCell fromCell = new PdfPCell();
            fromCell.setBorder(Rectangle.NO_BORDER);
            fromCell.setBackgroundColor(CARD_BG);
            fromCell.setPadding(0);

            Paragraph fromLabel = new Paragraph("FROM", labelFont);
            fromCell.addElement(fromLabel);

            String merchantEmail = getUserEmail(invoice.getMerchantId()).orElse("Unknown Merchant");
            Paragraph merchantName = new Paragraph(merchantEmail, boldValue);
            merchantName.setSpacingBefore(3);
            fromCell.addElement(merchantName);
            fromCell.addElement(Chunk.NEWLINE);

            Paragraph billToLabel = new Paragraph("BILL TO", labelFont);
            fromCell.addElement(billToLabel);
            Paragraph customerName = new Paragraph(invoice.getCustomerEmail(), boldValue);
            customerName.setSpacingBefore(3);
            fromCell.addElement(customerName);

            partyTable.addCell(fromCell);

            cardCell.addElement(partyTable);
            infoTable.addCell(cardCell);

            document.add(infoTable);
            document.add(Chunk.NEWLINE);
            document.add(Chunk.NEWLINE);

            // ============================================================
            // DESCRIPTION SECTION
            // ============================================================
            Paragraph descLabel = new Paragraph("DESCRIPTION", labelFont);
            document.add(descLabel);
            document.add(Chunk.NEWLINE);

            PdfPTable descTable = new PdfPTable(1);
            descTable.setWidthPercentage(100);

            PdfPCell descCell = new PdfPCell();
            descCell.setBorder(Rectangle.BOX);
            descCell.setBorderColor(BORDER);
            descCell.setBorderWidth(1);
            descCell.setBackgroundColor(LIGHT_BG);
            descCell.setPadding(14);

            String description = invoice.getDescription() != null
                ? invoice.getDescription() : "Invoice items";
            descCell.addElement(new Paragraph(description, valueFont));

            descTable.addCell(descCell);
            document.add(descTable);
            document.add(Chunk.NEWLINE);
            document.add(Chunk.NEWLINE);

            // ============================================================
            // TOTALS SECTION
            // ============================================================
            if (invoice.getTaxAmount() != null && invoice.getTaxAmount().compareTo(BigDecimal.ZERO) > 0) {
                PdfPTable taxTable = new PdfPTable(2);
                taxTable.setWidthPercentage(55);
                taxTable.setHorizontalAlignment(Element.ALIGN_RIGHT);
                taxTable.setWidths(new float[]{2, 1});

                addTotalsHeader(taxTable);

                addTotalsRow(taxTable, "Subtotal",
                    formatCurrency(invoice.getAmount(), invoice.getCurrency()),
                    valueFont, BORDER);

                String taxLabel = "Tax (" + invoice.getTaxName() + " " +
                    formatRate(invoice.getTaxRate()) + "%)";
                addTotalsRow(taxTable, taxLabel,
                    formatCurrency(invoice.getTaxAmount(), invoice.getCurrency()),
                    valueFont, BORDER);

                addTotalRow(taxTable, "Total",
                    formatCurrency(invoice.getTotalWithTax(), invoice.getCurrency()),
                    totalFont);

                document.add(taxTable);
            } else {
                PdfPTable amountTable = new PdfPTable(2);
                amountTable.setWidthPercentage(55);
                amountTable.setHorizontalAlignment(Element.ALIGN_RIGHT);
                amountTable.setWidths(new float[]{2, 1});

                addTotalRow(amountTable, "Total",
                    formatCurrency(invoice.getAmount(), invoice.getCurrency()),
                    totalFont);

                document.add(amountTable);
            }

            document.add(Chunk.NEWLINE);
            document.add(Chunk.NEWLINE);
            document.add(Chunk.NEWLINE);

            // ============================================================
            // FOOTER
            // ============================================================
            PdfPTable footerTable = new PdfPTable(1);
            footerTable.setWidthPercentage(100);

            PdfPCell footerCell = new PdfPCell();
            footerCell.setBorder(Rectangle.TOP);
            footerCell.setBorderColor(BORDER);
            footerCell.setBorderWidth(1);
            footerCell.setPadding(20);
            footerCell.setBackgroundColor(LIGHT_BG);

            Paragraph thankYou = new Paragraph("Thank you for your business!",
                makeFont(12, Font.BOLD, NAVY));
            thankYou.setAlignment(Element.ALIGN_CENTER);
            footerCell.addElement(thankYou);

            footerCell.addElement(Chunk.NEWLINE);

            Paragraph footerText = new Paragraph(
                "This invoice was generated automatically by PayFlow Enterprise Transaction Platform.\n" +
                "For any questions regarding this invoice, please contact the merchant directly.",
                makeFont(8, Font.NORMAL, SLATE_MUTED));
            footerText.setAlignment(Element.ALIGN_CENTER);
            footerText.setLeading(12);
            footerCell.addElement(footerText);

            footerCell.addElement(Chunk.NEWLINE);

            Paragraph copyright = new Paragraph(
                "© " + java.time.Year.now().getValue() + " PayFlow. All rights reserved.",
                makeFont(7, Font.NORMAL, SLATE_MUTED));
            copyright.setAlignment(Element.ALIGN_CENTER);
            footerCell.addElement(copyright);

            footerTable.addCell(footerCell);
            document.add(footerTable);

            document.close();

        } catch (Exception e) {
            log.error("Failed to generate PDF for invoice {}", invoice.getId(), e);
            throw new RuntimeException("PDF generation failed", e);
        }

        return out.toByteArray();
    }

    // ============================================================
    // STATUS PILL — soft bg + dark text, matches web badges
    // ============================================================
    private PdfPCell createStatusPillCell(String status) {
        Color bg;
        Color text;
        String label;

        switch (status != null ? status.toUpperCase() : "UNKNOWN") {
            case "PAID":
            case "SETTLED":
                bg = MINT_SOFT;   text = new Color(6, 95, 70);   label = "SETTLED";  break;
            case "APPROVED":
                bg = BLUE_SOFT;   text = new Color(30, 64, 175); label = "APPROVED"; break;
            case "PENDING":
            case "PENDING_APPROVAL":
                bg = AMBER_SOFT;  text = new Color(146, 64, 14); label = "PENDING";  break;
            case "REJECTED":
            case "FAILED":
                bg = ROSE_SOFT;   text = new Color(153, 27, 27); label = "REJECTED"; break;
            case "REFUNDED":
                bg = PURPLE_SOFT; text = new Color(91, 33, 182); label = "REFUNDED"; break;
            default:
                bg = LIGHT_BG;    text = SLATE_MUTED;            label = status != null ? status : "UNKNOWN";
        }

        Font pillFont = makeFont(8, Font.BOLD, text);
        Paragraph pillText = new Paragraph(label, pillFont);
        pillText.setAlignment(Element.ALIGN_CENTER);

        PdfPCell cell = new PdfPCell(pillText);
        cell.setBackgroundColor(bg);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(6);
        cell.setPaddingLeft(14);
        cell.setPaddingRight(14);
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);

        return cell;
    }

    // ============================================================
    // TOTALS HELPERS
    // ============================================================
    private void addTotalsHeader(PdfPTable table) {
        Font headerFont = makeFont(8, Font.BOLD, SLATE_MUTED);

        PdfPCell left = new PdfPCell(new Phrase("ITEM", headerFont));
        left.setBorder(Rectangle.BOTTOM);
        left.setBorderColor(BORDER);
        left.setBorderWidth(1);
        left.setPadding(10);
        left.setBackgroundColor(LIGHT_BG);
        table.addCell(left);

        PdfPCell right = new PdfPCell(new Phrase("AMOUNT", headerFont));
        right.setBorder(Rectangle.BOTTOM);
        right.setBorderColor(BORDER);
        right.setBorderWidth(1);
        right.setPadding(10);
        right.setBackgroundColor(LIGHT_BG);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(right);
    }

    private void addTotalsRow(PdfPTable table, String label, String value, Font font, Color borderColor) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, font));
        labelCell.setBorder(Rectangle.BOTTOM);
        labelCell.setBorderColor(borderColor);
        labelCell.setBorderWidth(1);
        labelCell.setPadding(10);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, font));
        valueCell.setBorder(Rectangle.BOTTOM);
        valueCell.setBorderColor(borderColor);
        valueCell.setBorderWidth(1);
        valueCell.setPadding(10);
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(valueCell);
    }

    private void addTotalRow(PdfPTable table, String label, String value, Font font) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, font));
        labelCell.setBorder(Rectangle.TOP);
        labelCell.setBorderColor(NAVY);
        labelCell.setBorderWidth(2);
        labelCell.setPadding(12);
        labelCell.setBackgroundColor(LIGHT_BG);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, font));
        valueCell.setBorder(Rectangle.TOP);
        valueCell.setBorderColor(NAVY);
        valueCell.setBorderWidth(2);
        valueCell.setPadding(12);
        valueCell.setBackgroundColor(LIGHT_BG);
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(valueCell);
    }

    // ============================================================
    // CURRENCY FORMATTING
    // Delegates to the shared CurrencySymbols class so the PDF
    // matches the web app's currency rendering exactly.
    // ============================================================
    private String formatCurrency(BigDecimal amount, String currency) {
        if (amount == null) return "—";
        String symbol = getCurrencySymbol(currency);
        return symbol + " " + String.format("%,.2f", amount);
    }

    private String getCurrencySymbol(String currencyCode) {
        // Delegates to the shared CurrencySymbols class so the PDF matches
        // the web app exactly — supports 160+ currencies.
        // Returns the currency code itself if unknown.
        return CurrencySymbols.getSymbol(currencyCode);
    }

    private String formatRate(BigDecimal rate) {
        if (rate == null) return "0";
        if (rate.stripTrailingZeros().scale() <= 0) {
            return rate.toBigInteger().toString();
        }
        return rate.stripTrailingZeros().toPlainString();
    }

    private Optional<String> getUserEmail(Long userId) {
        return userRepository.findById(userId).map(AppUser::getEmail);
    }
}