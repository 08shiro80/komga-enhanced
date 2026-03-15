-- Add scanlation group tracking to download items

ALTER TABLE DOWNLOAD_ITEM ADD COLUMN SCANLATION_GROUP varchar;

CREATE INDEX idx__download_item__group ON DOWNLOAD_ITEM (SCANLATION_GROUP);
