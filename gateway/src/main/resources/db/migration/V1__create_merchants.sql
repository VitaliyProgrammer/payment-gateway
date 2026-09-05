-- A merchant is an API client of the gateway: an online shop that submits payments
-- with an API key and receives webhooks about their outcome. Everything else in the
-- schema will hang off this table, so it goes first.

create table merchants (
    id           uuid         primary key,
    name         text         not null,
    -- Only the hash is stored. The key itself is shown to the merchant once, at
    -- creation, and is not recoverable afterwards - the same reason passwords are
    -- never stored in plain text.
    api_key_hash text         not null,
    -- Where payment webhooks are delivered. Nullable: a merchant may integrate by
    -- polling GET /v1/payments/{id} instead of receiving callbacks.
    webhook_url  text,
    active       boolean      not null default true,
    created_at   timestamptz  not null default now(),

    constraint merchants_name_not_blank check (length(btrim(name)) > 0)
);

-- Every authenticated request looks a merchant up by this hash, so it is indexed.
-- Unique because two merchants sharing an API key would make requests ambiguous.
create unique index merchants_api_key_hash_key on merchants (api_key_hash);
