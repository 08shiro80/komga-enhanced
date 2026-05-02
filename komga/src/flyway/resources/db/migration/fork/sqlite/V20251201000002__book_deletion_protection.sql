-- Add deletion protection for downloaded books

ALTER TABLE BOOK ADD COLUMN PROTECTED_FROM_DELETION boolean NOT NULL DEFAULT false;

CREATE INDEX idx__book__protected ON BOOK (PROTECTED_FROM_DELETION);
