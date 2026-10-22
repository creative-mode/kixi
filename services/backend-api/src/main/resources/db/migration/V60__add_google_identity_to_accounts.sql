ALTER TABLE accounts ADD COLUMN google_sub VARCHAR(255);

CREATE UNIQUE INDEX uq_accounts_google_sub
    ON accounts (google_sub)
    WHERE google_sub IS NOT NULL;
