-- Keep raw narrations and deduplication keys; repair only previously unparsed POS issuer payees.
WITH extracted AS (
  SELECT id, user_id, reason_key AS old_key, direction,
         trim(regexp_replace(
           regexp_replace(reason,
             '^\s*(?:(?:debit|credit|investment deduction)\s+and\s+)?(?:\d+\s*/\s*)*posiss\s*/\s*',
             '', 'i'),
           '(?:\s+|,\s*)(?:co\.?\s*,?\s*)?(?:ltd\.?|limited)\s*$', '', 'i')) AS payee
  FROM transactions
  WHERE merchant IS NULL
    AND reason ~* '^\s*(?:(?:debit|credit|investment deduction)\s+and\s+)?(?:\d+\s*/\s*)*posiss\s*/\s*.+$'
), cleaned AS (
  SELECT id, user_id, old_key, direction,
         regexp_replace(regexp_replace(payee, '\s+', ' ', 'g'), '[.\s]+$', '') AS payee
  FROM extracted
), repaired AS (
  UPDATE transactions t
  SET merchant = c.payee,
      payment_method = coalesce(t.payment_method, 'CARD'),
      reason_key = (CASE WHEN c.direction = 'CREDIT' THEN 'in:' ELSE '' END) ||
        trim(regexp_replace(
          regexp_replace(
            regexp_replace(lower(c.payee), '[^[:alpha:][:space:]]+', ' ', 'g'),
            '\m(pvt|private|ltd|limited|llp|inc)\M', ' ', 'g'),
          '\s+', ' ', 'g'))
  FROM cleaned c
  WHERE t.id = c.id AND c.payee ~ '[[:alpha:]]'
  RETURNING t.user_id, c.old_key, t.reason_key
)
-- Preserve learned categories for future imports under the corrected merchant key.
INSERT INTO reason_categories (user_id, reason_key, category, source, updated_at)
SELECT DISTINCT ON (r.user_id, r.reason_key)
       r.user_id, r.reason_key, rc.category, rc.source, rc.updated_at
FROM repaired r
JOIN reason_categories rc ON rc.user_id = r.user_id AND rc.reason_key = r.old_key
ORDER BY r.user_id, r.reason_key, (rc.source = 'manual') DESC, rc.updated_at DESC, rc.reason_key
ON CONFLICT (user_id, reason_key) DO NOTHING;
