-- One-off repair: some banks glue sentences without a space ("SIDDHANT ANAND.RRN 5145...Avl Bal ...-Indian Bank"),
-- so the saved payee ran on to the end of the SMS. Cut it at the glued tail and rebuild the merchant key the same way
-- Texts.normalizeReason does.
UPDATE transactions
SET bank = CASE WHEN bank IS NULL AND reason ~* '-indian bank\s*$' THEN 'Indian Bank' ELSE bank END,
    reason = regexp_replace(reason, '\.(rrn|ref|avl|avail|upi|not you)\M.*$', '', 'i')
WHERE reason ~* '[^.\s]\.(rrn|ref|avl|avail|upi|not you)\M';

UPDATE transactions
SET reason_key = (CASE WHEN direction = 'CREDIT' THEN 'in:' ELSE '' END) || trim(regexp_replace(
      regexp_replace(
        regexp_replace(lower(reason), '[^[:alpha:][:space:]]+', ' ', 'g'),
        '\m(pvt|private|ltd|limited|llp|inc)\M', ' ', 'g'),
      '\s+', ' ', 'g'))
WHERE merchant IS NULL AND reason_key ~ '\m(rrn|avl)\M';
