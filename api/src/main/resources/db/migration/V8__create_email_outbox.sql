-- Emails written in the same transaction as the change they report, sent after commit and retried
-- by the sweep (ADR-0004, docs/ERD.md). The text is written when the email is queued, so a later
-- change (a renamed room) cannot alter an email that is already waiting.

CREATE TABLE email_outbox (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    to_address      text NOT NULL,
    to_name         text,
    kind            text NOT NULL,
    subject         text NOT NULL,
    text_body       text NOT NULL,
    html_body       text NOT NULL,
    -- Stops the same email being queued twice, for example one suspension notice per suspension.
    dedupe_key      text,
    status          text NOT NULL DEFAULT 'PENDING',
    attempts        smallint NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    sent_at         timestamptz,

    CONSTRAINT outbox_status_valid CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    CONSTRAINT outbox_sent_consistent CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT outbox_attempts_valid CHECK (attempts >= 0),
    CONSTRAINT outbox_error_length CHECK (char_length(last_error) <= 500)
);

CREATE INDEX outbox_pending ON email_outbox (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX outbox_sent_at ON email_outbox (sent_at) WHERE status = 'SENT';
CREATE UNIQUE INDEX outbox_dedupe ON email_outbox (dedupe_key) WHERE dedupe_key IS NOT NULL;
