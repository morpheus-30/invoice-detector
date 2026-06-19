package com.invoicedetector.sdk.text

import java.util.Locale

/**
 * Multilingual keyword sets used by both the invoice classifier and the field
 * parser. Covers the major European languages that ML Kit's Latin OCR can read:
 * English, French, German, Spanish, Italian, Dutch, Portuguese, Polish, Swedish,
 * Danish, Norwegian and Finnish.
 *
 * Matching is accent- and case-insensitive. Short, ambiguous terms (e.g. "vat",
 * "iva", "no") are matched on word boundaries to avoid firing inside unrelated
 * words; longer / multi-word terms use a substring match.
 */
object InvoiceLexicon {

    /** A named bundle of synonyms with a precompiled matcher. */
    class Category internal constructor(
        val name: String,
        val synonyms: List<String>
    ) {
        private val terms: List<String> = synonyms
        private val regex: Regex = buildRegex(terms)

        /** True if any synonym appears in [lowercasedText]. */
        fun matches(lowercasedText: String): Boolean = regex.containsMatchIn(lowercasedText)

        /** The distinct synonyms found, for debugging / UX. */
        fun found(lowercasedText: String): List<String> =
            terms.filter { term -> Regex(escapeWithBoundary(term)).containsMatchIn(lowercasedText) }
                .distinct()
    }

    // "invoice"
    val INVOICE = Category(
        "invoice",
        listOf(
            "invoice", "tax invoice", "facture", "rechnung", "factura", "fattura",
            "factuur", "fatura", "faktura", "faktúra", "lasku", "regning", "regnskap"
        )
    )

    // "receipt" / "ticket" — photographed store receipts often say this, not "invoice".
    val RECEIPT = Category(
        "receipt",
        listOf(
            "receipt", "reçu", "recu", "ticket", "ticket de caisse", "quittung",
            "beleg", "kassenbon", "bon", "kassabon", "recibo", "ricevuta",
            "scontrino", "kvitto", "kvittering", "kuitti", "paragon"
        )
    )

    // "total"
    val TOTAL = Category(
        "total",
        listOf(
            "total", "totaal", "totale", "total ttc", "gesamt", "gesamtbetrag",
            "gesamtsumme", "summe", "suma", "summa", "yhteensä", "importe total",
            "total a pagar", "total general", "à payer", "te betalen"
        )
    )

    val SUBTOTAL = Category(
        "subtotal",
        listOf(
            "subtotal", "sub total", "sub-total", "sous-total", "zwischensumme",
            "subtotaal", "subtotale", "suma parcial", "delsumma", "netto"
        )
    )

    // VAT / sales tax
    val TAX = Category(
        "tax",
        listOf(
            "tax", "sales tax", "vat", "tva", "mwst", "mwst.", "ust", "ust.",
            "mehrwertsteuer", "iva", "btw", "moms", "alv", "mva", "impuesto",
            "imposta", "podatek", "gst", "cgst", "sgst", "igst"
        )
    )

    val AMOUNT_DUE = Category(
        "amount_due",
        listOf(
            "amount due", "balance due", "total due", "amount payable", "montant dû",
            "montant du", "net à payer", "zu zahlen", "zahlbetrag", "importe a pagar",
            "importe a abonar", "totale dovuto", "te betalen", "a pagar",
            "saldo", "att betala"
        )
    )

    val DATE_LABEL = Category(
        "date",
        listOf(
            "date", "datum", "fecha", "data", "rechnungsdatum", "date facture",
            "fecha factura", "invoice date", "issue date", "päivämäärä"
        )
    )

    val QTY = Category(
        "qty",
        listOf(
            "qty", "quantity", "menge", "anzahl", "cantidad", "quantité",
            "quantità", "aantal", "hoeveelheid", "ilość", "antal", "määrä", "qta"
        )
    )

    val PRICE = Category(
        "price",
        listOf(
            "price", "unit price", "prix", "preis", "precio", "prezzo", "prijs",
            "preço", "cena", "pris", "hinta", "p.u.", "amount", "montant", "betrag",
            "importe", "importo", "bedrag"
        )
    )

    /** Lower-cases once (locale-independent) for matching. */
    fun normalize(text: String): String = text.lowercase(Locale.ROOT)

    private fun buildRegex(terms: List<String>): Regex {
        val alternation = terms.joinToString("|") { escapeWithBoundary(it) }
        return Regex(alternation, RegexOption.IGNORE_CASE)
    }

    /**
     * Escapes a term and, when it is a single short ASCII word, wraps it in word
     * boundaries so e.g. "vat" doesn't match inside "private".
     */
    private fun escapeWithBoundary(term: String): String {
        val escaped = Regex.escape(term)
        val isShortAsciiWord = term.length <= 5 &&
            term.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        return if (isShortAsciiWord) "\\b$escaped\\b" else escaped
    }
}
