-- Транзакційний outbox: подія записується в ТУ САМУ транзакцію, що й сама
-- зміна стану платежу (див. PaymentProcessingService, PaymentTransitionGuard,
-- OutboxEventRecorder) - тож або обидва записи комітяться разом, або жоден.
-- Неможливо змінити платіж і "забути" покласти подію в чергу на доставку, чи
-- навпаки повідомити про зміну, якої насправді не відбулось.
create table outbox_events (
    id               uuid         primary key,
    payment_id       uuid         not null references payments (id),
    merchant_id      uuid         not null references merchants (id),
    event_type       text         not null,
    payload          text         not null,
    status           text         not null check (status in ('PENDING', 'DELIVERED', 'DEAD_LETTER')),
    attempts         integer      not null default 0,
    -- Коли подія знову стає доступною для спроби доставки. Виконує подвійну
    -- роль: і експоненційний backoff між невдалими спробами, і "оренда" на
    -- час активної доставки (next_attempt_at відсувається в майбутнє одразу
    -- при захопленні події, ще ДО фактичного HTTP-виклику). Якщо поллер
    -- впаде посеред доставки, оренда мине сама і подію підхопить хтось інший
    -- - жодна подія не застрягає назавжди через мертвий процес.
    next_attempt_at  timestamptz  not null default now(),
    last_error       text,
    created_at       timestamptz  not null default now(),
    delivered_at     timestamptz
);

-- Частковий індекс лише на PENDING-рядках: підтримує і вибірку по
-- (status, next_attempt_at) для самого поллера, і DISTINCT ON (payment_id) у
-- підзапиті репозиторію, який гарантує впорядковану per-платіж доставку.
create index outbox_events_pending_idx on outbox_events (status, next_attempt_at, payment_id, created_at)
    where status = 'PENDING';

create index outbox_events_payment_id_idx on outbox_events (payment_id);
