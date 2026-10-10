-- Issue #107 (BE-11): the server owns the clock of a simulation. An account
-- flagged here gets +25% on the statement duration as accessibility extra
-- time; the flag is written only by an administrator.
ALTER TABLE accounts
    ADD COLUMN accessibility_extra_time BOOLEAN NOT NULL DEFAULT FALSE;