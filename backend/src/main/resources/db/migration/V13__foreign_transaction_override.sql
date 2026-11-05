-- NULL uses the original currency; true/false explicitly override automatic detection.
ALTER TABLE transactions ADD COLUMN foreign_transaction_override BOOLEAN;
